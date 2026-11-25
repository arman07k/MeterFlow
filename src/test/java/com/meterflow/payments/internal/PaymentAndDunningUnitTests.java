package com.meterflow.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DuplicatePaymentSettlementException;
import com.meterflow.payments.api.IneligibleInvoiceForPaymentException;
import com.meterflow.payments.api.InitiatePaymentCommand;
import com.meterflow.payments.api.InvalidPaymentRequestException;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentFailedEvent;
import com.meterflow.payments.api.PaymentOverpaymentException;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.payments.api.PaymentSucceededEvent;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class PaymentAndDunningUnitTests {

    private static final Instant T0 = Instant.parse("2026-04-01T10:00:00Z");

    @Test
    void mockPaymentOutcomeClassifiesSuccessRetryableAndNonRetryableCorrectly() {
        assertThat(MockPaymentOutcome.SIMULATE_SUCCESS.isSuccess()).isTrue();
        assertThat(MockPaymentOutcome.SIMULATE_SUCCESS.isRetryable()).isFalse();

        assertThat(MockPaymentOutcome.SIMULATE_SOFT_DECLINE.isSuccess()).isFalse();
        assertThat(MockPaymentOutcome.SIMULATE_SOFT_DECLINE.isRetryable()).isTrue();
        assertThat(MockPaymentOutcome.SIMULATE_SOFT_DECLINE.failureCode()).isEqualTo("SOFT_DECLINE");

        assertThat(MockPaymentOutcome.SIMULATE_TIMEOUT.isSuccess()).isFalse();
        assertThat(MockPaymentOutcome.SIMULATE_TIMEOUT.isRetryable()).isTrue();
        assertThat(MockPaymentOutcome.SIMULATE_TIMEOUT.failureCode()).isEqualTo("GATEWAY_TIMEOUT");

        assertThat(MockPaymentOutcome.SIMULATE_HARD_DECLINE.isSuccess()).isFalse();
        assertThat(MockPaymentOutcome.SIMULATE_HARD_DECLINE.isRetryable()).isFalse();
        assertThat(MockPaymentOutcome.SIMULATE_HARD_DECLINE.failureCode()).isEqualTo("HARD_DECLINE");

        MockPaymentGateway gateway = new MockPaymentGateway(MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(gateway.resolveOutcome(null)).isEqualTo(MockPaymentOutcome.SIMULATE_SOFT_DECLINE);
        assertThat(gateway.resolveOutcome(MockPaymentOutcome.SIMULATE_SUCCESS)).isEqualTo(MockPaymentOutcome.SIMULATE_SUCCESS);
    }

    @Test
    void dunningScheduleCalculatorEnforcesExactDay1Day3Day7OffsetsAndZeroFourthRetry() {
        List<Integer> offsets = DunningScheduleCalculator.DEFAULT_RETRY_OFFSETS_HOURS;
        assertThat(offsets).containsExactly(24, 72, 168);

        assertThat(DunningScheduleCalculator.computeNextRetryAt(T0, 0, offsets))
                .contains(T0.plus(Duration.ofHours(24)));
        assertThat(DunningScheduleCalculator.computeNextRetryAt(T0, 1, offsets))
                .contains(T0.plus(Duration.ofHours(72)));
        assertThat(DunningScheduleCalculator.computeNextRetryAt(T0, 2, offsets))
                .contains(T0.plus(Duration.ofHours(168)));
        // Retry 3 exhausted -> strictly empty (never a 4th payment retry)
        assertThat(DunningScheduleCalculator.computeNextRetryAt(T0, 3, offsets)).isEmpty();
        assertThat(DunningScheduleCalculator.computeSuspensionAt(T0, 336))
                .isEqualTo(T0.plus(Duration.ofHours(336)));

        assertThatThrownBy(() -> DunningScheduleCalculator.validateSchedule(List.of(24, 72, 168, 336), 336))
                .isInstanceOf(InvalidPaymentRequestException.class);
        assertThatThrownBy(() -> DunningScheduleCalculator.validateSchedule(List.of(24, 72, 168), 100))
                .isInstanceOf(InvalidPaymentRequestException.class);
        assertThatThrownBy(() -> DunningScheduleCalculator.validateSchedule(List.of(72, 24, 168), 336))
                .isInstanceOf(InvalidPaymentRequestException.class);
    }

    @Test
    void dunningCaseStatusAndEntityEnforceValidStateTransitionsAndRetryBounds() {
        assertThat(DunningCaseStatus.RETRY_SCHEDULED.canTransitionTo(DunningCaseStatus.FINAL_WARNING)).isTrue();
        assertThat(DunningCaseStatus.RETRY_SCHEDULED.canTransitionTo(DunningCaseStatus.RECOVERED)).isTrue();
        assertThat(DunningCaseStatus.RETRY_SCHEDULED.canTransitionTo(DunningCaseStatus.SUSPENDED)).isFalse();
        assertThat(DunningCaseStatus.FINAL_WARNING.canTransitionTo(DunningCaseStatus.SUSPENDED)).isTrue();
        assertThat(DunningCaseStatus.SUSPENDED.canTransitionTo(DunningCaseStatus.RECOVERED)).isTrue();
        assertThat(DunningCaseStatus.RECOVERED.allowedTargets()).isEmpty();
        assertThat(PaymentStatus.CAPTURED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
        assertThat(PaymentStatus.PENDING.isTerminal()).isFalse();

        DunningCaseEntity entity = new DunningCaseEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                DunningCaseStatus.RETRY_SCHEDULED, T0, T0.plus(Duration.ofHours(24)), null,
                "SOFT_DECLINE", "Declined", T0
        );
        entity.recordRetryFailureAndScheduleNext(1, T0.plus(Duration.ofHours(72)), "SOFT_DECLINE", "Declined", T0);
        assertThat(entity.getRetriesCompleted()).isEqualTo(1);

        assertThatThrownBy(() -> entity.recordRetryFailureAndScheduleNext(4, T0, "SOFT_DECLINE", "Bad", T0))
                .isInstanceOf(IllegalStateException.class);

        entity.escalateToFinalWarning(3, T0.plus(Duration.ofHours(336)), "SOFT_DECLINE", "Exhausted", T0);
        assertThat(entity.getStatus()).isEqualTo(DunningCaseStatus.FINAL_WARNING);
        assertThat(entity.getNextRetryAt()).isNull();
        assertThat(entity.getSuspensionAt()).isEqualTo(T0.plus(Duration.ofHours(336)));

        entity.markSuspended(T0.plus(Duration.ofHours(336)), T0.plus(Duration.ofHours(336)));
        assertThat(entity.getStatus()).isEqualTo(DunningCaseStatus.SUSPENDED);

        entity.markRecovered(T0.plus(Duration.ofHours(340)), T0.plus(Duration.ofHours(340)));
        assertThat(entity.getStatus()).isEqualTo(DunningCaseStatus.RECOVERED);
        assertThat(entity.getNextRetryAt()).isNull();
        assertThat(entity.getSuspensionAt()).isNull();
    }

    @Test
    void paymentProcessingServiceValidatesEligibilityOverpaymentCurrencyAndAlreadyPaidInvoices() {
        PaymentTransactionRepository txRepo = mock(PaymentTransactionRepository.class);
        PaymentAttemptRepository attemptRepo = mock(PaymentAttemptRepository.class);
        DunningCaseRepository caseRepo = mock(DunningCaseRepository.class);
        DunningEventRepository eventRepo = mock(DunningEventRepository.class);
        DunningPolicyRepository policyRepo = mock(DunningPolicyRepository.class);
        DunningPolicyService policyService = mock(DunningPolicyService.class);
        InvoiceManagementFacade invoiceFacade = mock(InvoiceManagementFacade.class);
        SubscriptionQueryFacade subQueryFacade = mock(SubscriptionQueryFacade.class);
        SubscriptionManagementFacade subMgmtFacade = mock(SubscriptionManagementFacade.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);

        PaymentProcessingService service = new PaymentProcessingService(
                txRepo, attemptRepo, caseRepo, eventRepo, policyRepo, policyService,
                new MockPaymentGateway(MockPaymentOutcome.SIMULATE_SUCCESS),
                invoiceFacade, subQueryFacade, subMgmtFacade, publisher, metrics, fixedClock
        );

        UUID invoiceId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();

        // 1. Already PAID invoice -> DuplicatePaymentSettlementException
        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(
                invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.USD, Money.usd("0.0000"), InvoiceStatus.PAID)));
        assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "k-paid")))
                .isInstanceOf(DuplicatePaymentSettlementException.class);

        // 2. DRAFT invoice -> IneligibleInvoiceForPaymentException
        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(
                invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.USD, Money.usd("50.0000"), InvoiceStatus.DRAFT)));
        assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "k-draft")))
                .isInstanceOf(IneligibleInvoiceForPaymentException.class);

        // 3. VOID invoice -> IneligibleInvoiceForPaymentException
        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(
                invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.USD, Money.usd("50.0000"), InvoiceStatus.VOID)));
        assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "k-void")))
                .isInstanceOf(IneligibleInvoiceForPaymentException.class);

        // 4. Zero-due FINALIZED invoice -> IneligibleInvoiceForPaymentException
        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(
                invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.USD, Money.usd("0.0000"), InvoiceStatus.FINALIZED)));
        assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, null, null, MockPaymentOutcome.SIMULATE_SUCCESS, "k-zero")))
                .isInstanceOf(IneligibleInvoiceForPaymentException.class);

        // 5. Currency mismatch -> CurrencyMismatchException
        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(
                invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.BDT, Money.bdt("100.0000"), InvoiceStatus.FINALIZED)));
        assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, new BigDecimal("100.00"), SupportedCurrency.USD, MockPaymentOutcome.SIMULATE_SUCCESS, "k-curr")))
                .isInstanceOf(CurrencyMismatchException.class);

        // 6. Overpayment -> PaymentOverpaymentException
        assertThatThrownBy(() -> service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, new BigDecimal("100.01"), SupportedCurrency.BDT, MockPaymentOutcome.SIMULATE_SUCCESS, "k-over")))
                .isInstanceOf(PaymentOverpaymentException.class);
    }

    @Test
    void paymentSuccessDoesNotReactivateCancelledOrExpiredSubscriptionsAndEmitsSuccessEvent() {
        PaymentTransactionRepository txRepo = mock(PaymentTransactionRepository.class);
        PaymentAttemptRepository attemptRepo = mock(PaymentAttemptRepository.class);
        DunningCaseRepository caseRepo = mock(DunningCaseRepository.class);
        DunningEventRepository eventRepo = mock(DunningEventRepository.class);
        DunningPolicyRepository policyRepo = mock(DunningPolicyRepository.class);
        DunningPolicyService policyService = mock(DunningPolicyService.class);
        InvoiceManagementFacade invoiceFacade = mock(InvoiceManagementFacade.class);
        SubscriptionQueryFacade subQueryFacade = mock(SubscriptionQueryFacade.class);
        SubscriptionManagementFacade subMgmtFacade = mock(SubscriptionManagementFacade.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
        Clock fixedClock = Clock.fixed(T0, ZoneOffset.UTC);

        PaymentProcessingService service = new PaymentProcessingService(
                txRepo, attemptRepo, caseRepo, eventRepo, policyRepo, policyService,
                new MockPaymentGateway(MockPaymentOutcome.SIMULATE_SUCCESS),
                invoiceFacade, subQueryFacade, subMgmtFacade, publisher, metrics, fixedClock
        );

        UUID invoiceId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();

        InvoiceSummary openInvoice = invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.BDT, Money.bdt("250.0000"), InvoiceStatus.PAST_DUE);
        InvoiceSummary paidInvoice = invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.BDT, Money.bdt("0.0000"), InvoiceStatus.PAID);

        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(openInvoice));
        when(txRepo.findByCustomerIdAndIdempotencyKey(eq(customerId), anyString())).thenReturn(Optional.empty());
        when(txRepo.existsByInvoiceIdAndStatus(invoiceId, PaymentStatus.CAPTURED)).thenReturn(false);
        when(txRepo.nextPaymentReferenceSequence()).thenReturn(100001L);
        when(caseRepo.findByInvoiceIdForUpdate(invoiceId)).thenReturn(Optional.empty());
        when(invoiceFacade.recordPaymentSettlement(eq(invoiceId), eq(Money.bdt("250.0000")), anyString(), anyString()))
                .thenReturn(paidInvoice);
        when(txRepo.saveAndFlush(any(PaymentTransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attemptRepo.saveAndFlush(any(PaymentAttemptEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(subQueryFacade.findSubscriptionById(subId)).thenReturn(Optional.of(new SubscriptionSummary(
                subId, customerId, UUID.randomUUID(), SupportedCurrency.BDT, BillingInterval.MONTHLY,
                "UTC", 1, SubscriptionStatus.CANCELLED, null, null, TrialConversionPolicy.EXPIRE_ON_END,
                T0.minus(Duration.ofDays(30)), T0, null, null, false, T0.minus(Duration.ofDays(1)), T0, T0
        )));

        PaymentDetail result = service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, null, SupportedCurrency.BDT, MockPaymentOutcome.SIMULATE_SUCCESS, "pay-cancel-sub"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        verify(subMgmtFacade, never()).reactivateAfterPayment(any(), any(), any());
        verify(publisher).publishEvent(any(PaymentSucceededEvent.class));
    }

    @Test
    void idempotentReplayReturnsExistingPaymentWithoutReexecutingSettlementOrPublishingDuplicateEvents() {
        PaymentTransactionRepository txRepo = mock(PaymentTransactionRepository.class);
        PaymentAttemptRepository attemptRepo = mock(PaymentAttemptRepository.class);
        DunningCaseRepository caseRepo = mock(DunningCaseRepository.class);
        DunningEventRepository eventRepo = mock(DunningEventRepository.class);
        DunningPolicyRepository policyRepo = mock(DunningPolicyRepository.class);
        DunningPolicyService policyService = mock(DunningPolicyService.class);
        InvoiceManagementFacade invoiceFacade = mock(InvoiceManagementFacade.class);
        SubscriptionQueryFacade subQueryFacade = mock(SubscriptionQueryFacade.class);
        SubscriptionManagementFacade subMgmtFacade = mock(SubscriptionManagementFacade.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());

        PaymentProcessingService service = new PaymentProcessingService(
                txRepo, attemptRepo, caseRepo, eventRepo, policyRepo, policyService,
                new MockPaymentGateway(MockPaymentOutcome.SIMULATE_SUCCESS),
                invoiceFacade, subQueryFacade, subMgmtFacade, publisher, metrics, Clock.fixed(T0, ZoneOffset.UTC)
        );

        UUID invoiceId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        InvoiceSummary paidInvoice = invoiceSummary(invoiceId, customerId, subId, SupportedCurrency.USD, Money.usd("0.0000"), InvoiceStatus.PAID);
        PaymentTransactionEntity existing = new PaymentTransactionEntity(
                "PAY-2026-100001", invoiceId, customerId, subId, null, SupportedCurrency.USD,
                new BigDecimal("50.0000"), PaymentStatus.CAPTURED,
                com.meterflow.payments.api.PaymentAttemptSource.MANUAL, 0,
                MockPaymentOutcome.SIMULATE_SUCCESS, false, null, null, "idem-replay-1", T0, T0
        );

        when(invoiceFacade.findInvoiceById(invoiceId)).thenReturn(Optional.of(paidInvoice));
        when(txRepo.findByCustomerIdAndIdempotencyKey(customerId, "idem-replay-1")).thenReturn(Optional.of(existing));
        when(attemptRepo.findByPaymentTransactionIdOrderByAttemptedAtAsc(any())).thenReturn(List.of());

        PaymentDetail replayed = service.initiatePayment(new InitiatePaymentCommand(
                invoiceId, new BigDecimal("50.00"), SupportedCurrency.USD, MockPaymentOutcome.SIMULATE_SUCCESS, "idem-replay-1"));

        assertThat(replayed.payment().paymentReference()).isEqualTo("PAY-2026-100001");
        verify(invoiceFacade, never()).recordPaymentSettlement(any(), any(), any(), any());
        verify(publisher, never()).publishEvent(any(PaymentSucceededEvent.class));
        verify(publisher, never()).publishEvent(any(PaymentFailedEvent.class));
    }

    private static InvoiceSummary invoiceSummary(
            UUID invoiceId,
            UUID customerId,
            UUID subId,
            SupportedCurrency currency,
            Money due,
            InvoiceStatus status
    ) {
        Money zero = Money.zero(currency).roundToPersisted();
        return new InvoiceSummary(
                invoiceId, "INV-2026-100001", customerId, subId, null,
                T0.minus(Duration.ofDays(30)), T0, "UTC", T0, currency,
                due, due, zero, due, zero, zero, due, due.roundToPayable(),
                status, T0, T0.plus(Duration.ofDays(14)), null, null, T0, T0
        );
    }
}
