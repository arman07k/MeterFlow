package com.meterflow.portal.internal;

import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.CreditNoteLimitExceededException;
import com.meterflow.billing.api.ImmutableInvoiceException;
import com.meterflow.billing.api.InsufficientCreditBalanceException;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.InvalidInvoiceTransitionException;
import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.DuplicateCatalogKeyException;
import com.meterflow.catalog.api.ImmutablePlanVersionException;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.catalog.api.UnsupportedChargeTypeException;
import com.meterflow.identity.api.DuplicateCustomerKeyException;
import com.meterflow.identity.api.DuplicateEmailException;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.identity.api.InvalidMembershipException;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.metering.api.BatchLimitExceededException;
import com.meterflow.metering.api.IdempotencyConflictException;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.metering.api.MeteringResourceNotFoundException;
import com.meterflow.notifications.api.NotificationNotFoundException;
import com.meterflow.payments.api.DuplicatePaymentSettlementException;
import com.meterflow.payments.api.IneligibleInvoiceForPaymentException;
import com.meterflow.payments.api.InvalidPaymentRequestException;
import com.meterflow.payments.api.PaymentOverpaymentException;
import com.meterflow.payments.api.PaymentResourceNotFoundException;
import com.meterflow.shared.CorrelationIds;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.InvalidCurrencyException;
import com.meterflow.shared.InvalidMoneyException;
import com.meterflow.subscriptions.api.ConflictingSubscriptionException;
import com.meterflow.subscriptions.api.InvalidSubscriptionRequestException;
import com.meterflow.subscriptions.api.InvalidSubscriptionTransitionException;
import com.meterflow.subscriptions.api.SubscriptionNotFoundException;
import com.meterflow.webhooks.api.InvalidWebhookEndpointException;
import com.meterflow.webhooks.api.WebhookReplayNotAllowedException;
import com.meterflow.webhooks.api.WebhookResourceNotFoundException;
import java.net.URI;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single authoritative RFC 9457 ProblemDetail error adapter for REST API controllers (ADR-006, QA4, QA5).
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalApiExceptionHandler {

    @ExceptionHandler(InvalidCurrencyException.class)
    public ProblemDetail handleInvalidCurrency(InvalidCurrencyException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Invalid Currency",
                ex.getMessage(),
                "urn:meterflow:error:invalid-currency"
        );
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Currency Mismatch",
                ex.getMessage(),
                "urn:meterflow:error:currency-mismatch"
        );
    }

    @ExceptionHandler(InvalidMoneyException.class)
    public ProblemDetail handleInvalidMoney(InvalidMoneyException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Invalid Monetary Value",
                ex.getMessage(),
                "urn:meterflow:error:invalid-money"
        );
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ProblemDetail handleDuplicateEmail(DuplicateEmailException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Duplicate Email",
                "An account with this email already exists",
                "urn:meterflow:error:duplicate-email"
        );
    }

    @ExceptionHandler(DuplicateCustomerKeyException.class)
    public ProblemDetail handleDuplicateCustomerKey(DuplicateCustomerKeyException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Duplicate Customer Key",
                "A customer with this external key already exists",
                "urn:meterflow:error:duplicate-customer-key"
        );
    }

    @ExceptionHandler(InvalidMembershipException.class)
    public ProblemDetail handleInvalidMembership(InvalidMembershipException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Customer Membership",
                ex.getMessage(),
                "urn:meterflow:error:invalid-membership"
        );
    }

    @ExceptionHandler(IdentityResourceNotFoundException.class)
    public ProblemDetail handleIdentityNotFound(IdentityResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Resource Not Found",
                ex.getMessage(),
                "urn:meterflow:error:identity-not-found"
        );
    }

    @ExceptionHandler(CatalogResourceNotFoundException.class)
    public ProblemDetail handleCatalogNotFound(CatalogResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Catalogue Resource Not Found",
                ex.getMessage(),
                "urn:meterflow:error:catalog-not-found"
        );
    }

    @ExceptionHandler(DuplicateCatalogKeyException.class)
    public ProblemDetail handleDuplicateCatalogKey(DuplicateCatalogKeyException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Duplicate Catalogue Key",
                ex.getMessage(),
                "urn:meterflow:error:duplicate-catalog-key"
        );
    }

    @ExceptionHandler(ImmutablePlanVersionException.class)
    public ProblemDetail handleImmutablePlanVersion(ImmutablePlanVersionException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Immutable Plan Version",
                ex.getMessage(),
                "urn:meterflow:error:immutable-plan-version"
        );
    }

    @ExceptionHandler(InvalidCatalogConfigurationException.class)
    public ProblemDetail handleInvalidCatalogConfig(InvalidCatalogConfigurationException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Catalogue Configuration",
                ex.getMessage(),
                "urn:meterflow:error:invalid-catalog-configuration"
        );
    }

    @ExceptionHandler(InvalidRatingContextException.class)
    public ProblemDetail handleInvalidRatingContext(InvalidRatingContextException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Rating Context",
                ex.getMessage(),
                "urn:meterflow:error:invalid-rating-context"
        );
    }

    @ExceptionHandler(UnsupportedChargeTypeException.class)
    public ProblemDetail handleUnsupportedChargeType(UnsupportedChargeTypeException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Unsupported Charge Type",
                ex.getMessage(),
                "urn:meterflow:error:unsupported-charge-type"
        );
    }

    @ExceptionHandler(SubscriptionNotFoundException.class)
    public ProblemDetail handleSubscriptionNotFound(SubscriptionNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Subscription Not Found",
                ex.getMessage(),
                "urn:meterflow:error:subscription-not-found"
        );
    }

    @ExceptionHandler(ConflictingSubscriptionException.class)
    public ProblemDetail handleConflictingSubscription(ConflictingSubscriptionException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Conflicting Subscription State",
                ex.getMessage(),
                "urn:meterflow:error:conflicting-subscription"
        );
    }

    @ExceptionHandler(InvalidSubscriptionTransitionException.class)
    public ProblemDetail handleInvalidSubscriptionTransition(InvalidSubscriptionTransitionException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Invalid Subscription Transition",
                ex.getMessage(),
                "urn:meterflow:error:invalid-subscription-transition"
        );
    }

    @ExceptionHandler(InvalidSubscriptionRequestException.class)
    public ProblemDetail handleInvalidSubscriptionRequest(InvalidSubscriptionRequestException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Subscription Request",
                ex.getMessage(),
                "urn:meterflow:error:invalid-subscription-request"
        );
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Concurrent Modification Conflict",
                "The resource was concurrently modified; please retry",
                "urn:meterflow:error:concurrent-modification"
        );
    }

    @ExceptionHandler(MeteringResourceNotFoundException.class)
    public ProblemDetail handleMeteringNotFound(MeteringResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Metering Resource Not Found",
                ex.getMessage(),
                "urn:meterflow:error:metering-not-found"
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Idempotency Key Conflict",
                ex.getMessage(),
                "urn:meterflow:error:idempotency-key-conflict"
        );
    }

    @ExceptionHandler(BatchLimitExceededException.class)
    public ProblemDetail handleBatchLimitExceeded(BatchLimitExceededException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Batch Limit Exceeded",
                ex.getMessage(),
                "urn:meterflow:error:batch-limit-exceeded"
        );
    }

    @ExceptionHandler(InvalidMeteringRequestException.class)
    public ProblemDetail handleInvalidMeteringRequest(InvalidMeteringRequestException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Metering Request",
                ex.getMessage(),
                "urn:meterflow:error:invalid-metering-request"
        );
    }

    @ExceptionHandler(BillingResourceNotFoundException.class)
    public ProblemDetail handleBillingNotFound(BillingResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Billing Resource Not Found",
                ex.getMessage(),
                "urn:meterflow:error:billing-not-found"
        );
    }

    @ExceptionHandler(ImmutableInvoiceException.class)
    public ProblemDetail handleImmutableInvoice(ImmutableInvoiceException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Immutable Invoice",
                ex.getMessage(),
                "urn:meterflow:error:immutable-invoice"
        );
    }

    @ExceptionHandler(InvalidInvoiceTransitionException.class)
    public ProblemDetail handleInvalidInvoiceTransition(InvalidInvoiceTransitionException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Invalid Invoice Transition",
                ex.getMessage(),
                "urn:meterflow:error:invalid-invoice-transition"
        );
    }

    @ExceptionHandler(InvalidBillingRequestException.class)
    public ProblemDetail handleInvalidBillingRequest(InvalidBillingRequestException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Billing Request",
                ex.getMessage(),
                "urn:meterflow:error:invalid-billing-request"
        );
    }

    @ExceptionHandler(InsufficientCreditBalanceException.class)
    public ProblemDetail handleInsufficientCreditBalance(InsufficientCreditBalanceException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Insufficient Credit Balance",
                ex.getMessage(),
                "urn:meterflow:error:insufficient-credit-balance"
        );
    }

    @ExceptionHandler(CreditNoteLimitExceededException.class)
    public ProblemDetail handleCreditNoteLimitExceeded(CreditNoteLimitExceededException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Credit Note Limit Exceeded",
                ex.getMessage(),
                "urn:meterflow:error:credit-note-limit-exceeded"
        );
    }

    @ExceptionHandler(PaymentResourceNotFoundException.class)
    public ProblemDetail handlePaymentNotFound(PaymentResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Payment Resource Not Found",
                ex.getMessage(),
                "urn:meterflow:error:payment-not-found"
        );
    }

    @ExceptionHandler(DuplicatePaymentSettlementException.class)
    public ProblemDetail handleDuplicatePaymentSettlement(DuplicatePaymentSettlementException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Duplicate Payment Settlement",
                ex.getMessage(),
                "urn:meterflow:error:duplicate-payment-settlement"
        );
    }

    @ExceptionHandler(IneligibleInvoiceForPaymentException.class)
    public ProblemDetail handleIneligibleInvoiceForPayment(IneligibleInvoiceForPaymentException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Invoice Not Eligible For Payment",
                ex.getMessage(),
                "urn:meterflow:error:ineligible-invoice-for-payment"
        );
    }

    @ExceptionHandler(PaymentOverpaymentException.class)
    public ProblemDetail handlePaymentOverpayment(PaymentOverpaymentException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Payment Overpayment Rejected",
                ex.getMessage(),
                "urn:meterflow:error:payment-overpayment"
        );
    }

    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ProblemDetail handleInvalidPaymentRequest(InvalidPaymentRequestException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Payment Request",
                ex.getMessage(),
                "urn:meterflow:error:invalid-payment-request"
        );
    }

    @ExceptionHandler(WebhookResourceNotFoundException.class)
    public ProblemDetail handleWebhookNotFound(WebhookResourceNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Webhook Resource Not Found",
                ex.getMessage(),
                "urn:meterflow:error:webhook-not-found"
        );
    }

    @ExceptionHandler(InvalidWebhookEndpointException.class)
    public ProblemDetail handleInvalidWebhookEndpoint(InvalidWebhookEndpointException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Invalid Webhook Endpoint",
                ex.getMessage(),
                "urn:meterflow:error:invalid-webhook-endpoint"
        );
    }

    @ExceptionHandler(WebhookReplayNotAllowedException.class)
    public ProblemDetail handleWebhookReplayNotAllowed(WebhookReplayNotAllowedException ex) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Webhook Replay Not Allowed",
                ex.getMessage(),
                "urn:meterflow:error:webhook-replay-not-allowed"
        );
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ProblemDetail handleNotificationNotFound(NotificationNotFoundException ex) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Notification Not Found",
                ex.getMessage(),
                "urn:meterflow:error:notification-not-found"
        );
    }

    @ExceptionHandler({ UnauthorizedCustomerAccessException.class, AccessDeniedException.class })
    public ProblemDetail handleAccessDenied(RuntimeException ex) {
        return buildProblem(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "Access is denied",
                "urn:meterflow:error:access-denied"
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationFailure(AuthenticationException ex) {
        return buildProblem(
                HttpStatus.UNAUTHORIZED,
                "Authentication Failed",
                "Authentication is required or credentials are invalid",
                "urn:meterflow:error:authentication-failed"
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationError(MethodArgumentNotValidException ex) {
        return buildProblem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Validation Error",
                "Request validation failed",
                "urn:meterflow:error:validation"
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                ex.getMessage() != null ? ex.getMessage() : "Invalid request parameter",
                "urn:meterflow:error:bad-request"
        );
    }

    private ProblemDetail buildProblem(HttpStatus status, String title, String detailMessage, String typeUri) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                status,
                detailMessage != null ? detailMessage : title
        );
        detail.setTitle(title);
        detail.setType(URI.create(typeUri));
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            detail.setProperty(CorrelationIds.MDC_KEY, correlationId);
        }
        return detail;
    }
}
