package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.CreditGrantSummary;
import com.meterflow.billing.api.CreditTransactionRecord;
import com.meterflow.billing.api.CreditTransactionType;
import com.meterflow.billing.api.CustomerCreditBalance;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.IssueCreditGrantCommand;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PrepaidCreditService implements PrepaidCreditFacade {

    private static final int DEFAULT_PRIORITY = 100;

    private final PrepaidCreditGrantRepository grantRepository;
    private final CreditTransactionRepository transactionRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final BillingMetrics metrics;

    PrepaidCreditService(
            PrepaidCreditGrantRepository grantRepository,
            CreditTransactionRepository transactionRepository,
            IdentityQueryFacade identityQueryFacade,
            BillingMetrics metrics
    ) {
        this.grantRepository = grantRepository;
        this.transactionRepository = transactionRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public CreditGrantSummary issueCreditGrant(IssueCreditGrantCommand command) {
        if (command == null) {
            throw new InvalidBillingRequestException("Credit grant command must not be null");
        }
        if (command.customerId() == null) {
            throw new InvalidBillingRequestException("customerId must not be null");
        }
        if (command.currency() == null) {
            throw new InvalidBillingRequestException("currency must not be null");
        }
        if (command.grantType() == null) {
            throw new InvalidBillingRequestException("grantType must not be null");
        }
        if (command.description() == null || command.description().isBlank()) {
            throw new InvalidBillingRequestException("description must not be blank");
        }
        if (command.amount() == null) {
            throw new InvalidBillingRequestException("amount must not be null");
        }

        String idempotencyKey = normalizeOptionalKey(command.idempotencyKey());
        if (idempotencyKey != null) {
            var existing = grantRepository.findByCustomerIdAndIdempotencyKey(command.customerId(), idempotencyKey);
            if (existing.isPresent()) {
                return BillingMapper.toCreditGrantSummary(existing.get());
            }
        }

        CustomerSummary customer = identityQueryFacade.findCustomerById(command.customerId())
                .orElseThrow(() -> new BillingResourceNotFoundException(
                        "Customer not found: " + command.customerId()));
        if (customer.currency() != command.currency()) {
            throw new CurrencyMismatchException(customer.currency(), command.currency());
        }

        Money grantAmount = Money.of(command.amount(), command.currency()).roundToPersisted();
        if (!grantAmount.isPositive()) {
            throw new InvalidBillingRequestException("Credit grant amount must be positive");
        }

        Instant now = nowMicros();
        Instant expiresAt = command.expiresAt() == null ? null : command.expiresAt().truncatedTo(ChronoUnit.MICROS);
        int priority = command.priority() == null ? DEFAULT_PRIORITY : command.priority();

        PrepaidCreditGrantEntity grant = new PrepaidCreditGrantEntity(
                command.customerId(),
                command.currency(),
                command.grantType(),
                command.description().trim(),
                grantAmount.amount(),
                priority,
                expiresAt,
                idempotencyKey,
                now
        );
        PrepaidCreditGrantEntity saved = grantRepository.saveAndFlush(grant);

        CreditTransactionEntity tx = new CreditTransactionEntity(
                saved.getId(),
                saved.getCustomerId(),
                null,
                CreditTransactionType.ISSUED,
                grantAmount.amount(),
                saved.getRemainingAmount(),
                saved.getCurrency(),
                saved.getDescription(),
                "grant-issue:" + saved.getId(),
                now
        );
        transactionRepository.save(tx);
        metrics.recordCreditIssued();
        return BillingMapper.toCreditGrantSummary(saved);
    }

    /**
     * Atomically consumes eligible customer credit grants in deterministic FIFO order
     * (earliest expiry first, then priority, then creation time) under PostgreSQL
     * {@code SELECT ... FOR UPDATE} row locks to guarantee zero concurrent overdrafts.
     */
    @Transactional
    Money consumeCreditsForInvoice(
            UUID customerId,
            UUID invoiceId,
            SupportedCurrency currency,
            Money maxToConsume,
            Instant now
    ) {
        Money zero = Money.zero(currency).roundToPersisted();
        if (maxToConsume == null || !maxToConsume.isPositive()) {
            return zero;
        }

        Money remainingNeeded = maxToConsume.roundToPersisted();
        Money totalConsumed = zero;

        List<PrepaidCreditGrantEntity> eligibleGrants =
                grantRepository.findEligibleGrantsForUpdate(customerId, currency, now);

        for (PrepaidCreditGrantEntity grant : eligibleGrants) {
            if (!remainingNeeded.isPositive()) {
                break;
            }
            String txKey = "inv-credit:" + invoiceId + ":" + grant.getId();
            if (transactionRepository.findByCustomerIdAndIdempotencyKey(customerId, txKey).isPresent()) {
                continue;
            }

            Money availableOnGrant = Money.of(grant.getRemainingAmount(), currency).roundToPersisted();
            if (!availableOnGrant.isPositive()) {
                continue;
            }

            Money take = remainingNeeded.min(availableOnGrant).roundToPersisted();
            if (!take.isPositive()) {
                continue;
            }

            grant.deduct(take.amount(), now);
            grantRepository.saveAndFlush(grant);

            CreditTransactionEntity tx = new CreditTransactionEntity(
                    grant.getId(),
                    customerId,
                    invoiceId,
                    CreditTransactionType.CONSUMED,
                    take.amount(),
                    grant.getRemainingAmount(),
                    currency,
                    "Applied to invoice " + invoiceId,
                    txKey,
                    now
            );
            transactionRepository.save(tx);
            metrics.recordCreditConsumed();

            totalConsumed = totalConsumed.add(take).roundToPersisted();
            remainingNeeded = remainingNeeded.subtract(take).roundToPersisted();
        }

        return totalConsumed;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerCreditBalance getCustomerCreditBalance(UUID customerId, SupportedCurrency currency) {
        if (customerId == null) {
            throw new InvalidBillingRequestException("customerId must not be null");
        }
        CustomerSummary customer = identityQueryFacade.findCustomerById(customerId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Customer not found: " + customerId));
        SupportedCurrency effectiveCurrency = currency == null ? customer.currency() : currency;
        if (effectiveCurrency != customer.currency()) {
            throw new CurrencyMismatchException(customer.currency(), effectiveCurrency);
        }

        Instant now = nowMicros();
        List<PrepaidCreditGrantEntity> eligible =
                grantRepository.findEligibleGrantsReadOnly(customerId, effectiveCurrency, now);

        Money total = Money.zero(effectiveCurrency).roundToPersisted();
        for (PrepaidCreditGrantEntity grant : eligible) {
            total = total.add(Money.of(grant.getRemainingAmount(), effectiveCurrency)).roundToPersisted();
        }
        return new CustomerCreditBalance(customerId, effectiveCurrency, total, eligible.size(), now);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CreditGrantSummary> listCustomerCreditGrants(UUID customerId, Pageable pageable) {
        return grantRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(BillingMapper::toCreditGrantSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CreditTransactionRecord> listCustomerCreditTransactions(UUID customerId, Pageable pageable) {
        return transactionRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(BillingMapper::toCreditTransactionRecord);
    }

    private static String normalizeOptionalKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
