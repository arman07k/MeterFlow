package com.meterflow.webhooks.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterflow.webhooks.api.InvalidWebhookEndpointException;
import com.meterflow.webhooks.api.WebhookSignatureVerifier;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSecurityAndBackoffUnitTests {

    @Test
    void hmacSignatureVerifiesWithConstantTimeMatchingAndDualKeyRotation() {
        String activeSecret = "whsec_active_secret_key_for_unit_test_001";
        String previousSecret = "whsec_previous_secret_key_for_unit_test_002";
        String payload = "{\"eventType\":\"invoice.finalised\",\"amount\":\"1500.00\"}";
        Instant now = Instant.parse("2026-11-25T10:00:00Z");
        long epoch = now.getEpochSecond();

        String activeSig = WebhookSignatureVerifier.computeSignature(activeSecret, epoch, payload);
        String previousSig = WebhookSignatureVerifier.computeSignature(previousSecret, epoch, payload);
        String header = WebhookSignatureVerifier.buildSignatureHeader(epoch, List.of(activeSig, previousSig));

        assertThat(WebhookSignatureVerifier.verifySignature(header, payload, List.of(activeSecret), now)).isTrue();
        assertThat(WebhookSignatureVerifier.verifySignature(header, payload, List.of(previousSecret), now)).isTrue();
        assertThat(WebhookSignatureVerifier.verifySignature(header, payload + " ", List.of(activeSecret), now)).isFalse();
        assertThat(WebhookSignatureVerifier.verifySignature(header, payload, List.of("whsec_wrong_secret"), now)).isFalse();

        // Replay protection rejects signatures outside the 300-second window.
        assertThat(WebhookSignatureVerifier.verifySignature(
                header,
                payload,
                List.of(activeSecret),
                now.plusSeconds(301)
        )).isFalse();
        assertThat(WebhookSignatureVerifier.verifySignature(
                header,
                payload,
                List.of(activeSecret),
                now.minusSeconds(301)
        )).isFalse();
        assertThat(WebhookSignatureVerifier.verifySignature(
                header,
                payload,
                List.of(activeSecret),
                now.plusSeconds(300)
        )).isTrue();
    }

    @Test
    void aesGcmCipherEncryptsWithFreshIvAndDetectsTampering() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        WebhookSecretCipher cipher = new WebhookSecretCipher(key);

        String secret = cipher.generateSigningSecret();
        assertThat(secret).startsWith("whsec_");
        assertThat(secret.length()).isGreaterThan(40);

        String enc1 = cipher.encrypt(secret);
        String enc2 = cipher.encrypt(secret);
        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(cipher.decrypt(enc1)).isEqualTo(secret);
        assertThat(cipher.decrypt(enc2)).isEqualTo(secret);

        String tampered = enc1.substring(0, enc1.length() - 2) + "AA";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void ssrfValidatorBlocksLoopbackPrivateMetadataAndNonHttpsTargets() {
        SsrfUrlValidator strict = new SsrfUrlValidator(false);

        assertThatThrownBy(() -> strict.validateAndNormalize("http://hooks.example.com/events"))
                .isInstanceOf(InvalidWebhookEndpointException.class);
        assertThatThrownBy(() -> strict.validateAndNormalize("https://localhost/hook"))
                .isInstanceOf(InvalidWebhookEndpointException.class);
        assertThatThrownBy(() -> strict.validateAndNormalize("https://127.0.0.1:8080/hook"))
                .isInstanceOf(InvalidWebhookEndpointException.class);
        assertThatThrownBy(() -> strict.validateAndNormalize("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(InvalidWebhookEndpointException.class);
        assertThatThrownBy(() -> strict.validateAndNormalize("https://10.0.0.10/internal"))
                .isInstanceOf(InvalidWebhookEndpointException.class);
        assertThatThrownBy(() -> strict.validateAndNormalize("https://192.168.1.5/hook"))
                .isInstanceOf(InvalidWebhookEndpointException.class);
        assertThatThrownBy(() -> strict.validateAndNormalize("https://user:pass@hooks.example.com/hook"))
                .isInstanceOf(InvalidWebhookEndpointException.class);

        assertThat(strict.validateAndNormalize("https://hooks.example.com/meterflow").toString())
                .isEqualTo("https://hooks.example.com/meterflow");

        SsrfUrlValidator localAllowed = new SsrfUrlValidator(true);
        assertThat(localAllowed.validateAndNormalize("http://127.0.0.1:9999/webhook").toString())
                .isEqualTo("http://127.0.0.1:9999/webhook");
    }

    @Test
    void backoffScheduleMatchesAdr009AndClassifiesHttpStatusCodes() {
        Instant base = Instant.parse("2026-11-25T12:00:00Z");

        assertThat(WebhookBackoffPolicy.nextRetryAt(1, 6, base)).contains(base.plus(Duration.ofSeconds(30)));
        assertThat(WebhookBackoffPolicy.nextRetryAt(2, 6, base)).contains(base.plus(Duration.ofMinutes(2)));
        assertThat(WebhookBackoffPolicy.nextRetryAt(3, 6, base)).contains(base.plus(Duration.ofMinutes(10)));
        assertThat(WebhookBackoffPolicy.nextRetryAt(4, 6, base)).contains(base.plus(Duration.ofHours(1)));
        assertThat(WebhookBackoffPolicy.nextRetryAt(5, 6, base)).contains(base.plus(Duration.ofHours(6)));
        assertThat(WebhookBackoffPolicy.nextRetryAt(6, 6, base)).isEmpty();

        assertThat(WebhookBackoffPolicy.isSuccessStatus(200)).isTrue();
        assertThat(WebhookBackoffPolicy.isSuccessStatus(204)).isTrue();
        assertThat(WebhookBackoffPolicy.isSuccessStatus(500)).isFalse();

        assertThat(WebhookBackoffPolicy.isRetryableFailure(null)).isTrue();
        assertThat(WebhookBackoffPolicy.isRetryableFailure(408)).isTrue();
        assertThat(WebhookBackoffPolicy.isRetryableFailure(429)).isTrue();
        assertThat(WebhookBackoffPolicy.isRetryableFailure(503)).isTrue();
        assertThat(WebhookBackoffPolicy.isRetryableFailure(400)).isFalse();
        assertThat(WebhookBackoffPolicy.isRetryableFailure(404)).isFalse();
        assertThat(WebhookBackoffPolicy.isRetryableFailure(410)).isFalse();
    }
}
