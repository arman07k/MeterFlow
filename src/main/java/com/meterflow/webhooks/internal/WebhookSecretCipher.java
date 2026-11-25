package com.meterflow.webhooks.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Envelope AES-256-GCM cipher and CSPRNG secret generator for outbound webhook signing secrets (ADR-009).
 */
@Component
class WebhookSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretCipher.class);
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int SECRET_RANDOM_BYTES = 32;
    private static final String VERSION_PREFIX = "v1:";
    private static final String SECRET_PREFIX = "whsec_";

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey masterKey;

    @Autowired
    WebhookSecretCipher(WebhookProperties properties, Environment environment) {
        this.masterKey = resolveMasterKey(properties.getEncryptionKey(), environment);
    }

    WebhookSecretCipher(byte[] rawKeyBytes) {
        if (rawKeyBytes == null || rawKeyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 master key must be exactly 32 bytes");
        }
        this.masterKey = new SecretKeySpec(Arrays.copyOf(rawKeyBytes, 32), "AES");
    }

    String generateSigningSecret() {
        byte[] bytes = new byte[SECRET_RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String safePrefix(String rawSecret) {
        if (rawSecret == null || rawSecret.length() <= 14) {
            return "whsec_****";
        }
        return rawSecret.substring(0, 14) + "...";
    }

    String encrypt(String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            throw new IllegalArgumentException("Signing secret to encrypt must not be blank");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintextSecret.getBytes(StandardCharsets.UTF_8));
            ByteBuffer combined = ByteBuffer.allocate(iv.length + ciphertext.length);
            combined.put(iv);
            combined.put(ciphertext);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined.array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt webhook signing secret", ex);
        }
    }

    String decrypt(String encryptedValue) {
        if (encryptedValue == null || !encryptedValue.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("Unsupported encrypted webhook secret format");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedValue.substring(VERSION_PREFIX.length()));
            if (combined.length <= GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Corrupted encrypted webhook secret payload");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to decrypt webhook signing secret", ex);
        }
    }

    private static SecretKey resolveMasterKey(String configuredKey, Environment environment) {
        String trimmed = configuredKey != null ? configuredKey.trim() : "";
        if (!trimmed.isEmpty()) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(trimmed);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "meterflow.webhooks.encryption-key must be a valid Base64-encoded 32-byte key",
                        ex
                );
            }
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "meterflow.webhooks.encryption-key must decode to exactly 32 bytes (256 bits)"
                );
            }
            return new SecretKeySpec(decoded, "AES");
        }

        boolean supabaseProfileActive = Arrays.asList(environment.getActiveProfiles()).contains("supabase");
        if (supabaseProfileActive) {
            throw new IllegalStateException(
                    "WEBHOOK_SECRET_ENCRYPTION_KEY must be configured when running with the supabase profile"
            );
        }

        log.warn("WEBHOOK_SECRET_ENCRYPTION_KEY is not configured; using a non-production local fallback AES-256 key");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] derived = sha256.digest("meterflow-local-dev-webhook-key-do-not-use-in-prod".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable for local key derivation", ex);
        }
    }
}
