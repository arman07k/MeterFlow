package com.meterflow.portal.internal;

import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.payments.api.ConfigureDunningPolicyCommand;
import com.meterflow.payments.api.DunningCaseDetail;
import com.meterflow.payments.api.DunningCaseStatus;
import com.meterflow.payments.api.DunningCaseSummary;
import com.meterflow.payments.api.DunningExecutionSummary;
import com.meterflow.payments.api.DunningManagementFacade;
import com.meterflow.payments.api.DunningPolicyFacade;
import com.meterflow.payments.api.DunningPolicySummary;
import com.meterflow.payments.api.InitiatePaymentCommand;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentAttemptRecord;
import com.meterflow.payments.api.PaymentAttemptSource;
import com.meterflow.payments.api.PaymentDetail;
import com.meterflow.payments.api.PaymentProcessingFacade;
import com.meterflow.payments.api.PaymentQueryFacade;
import com.meterflow.payments.api.PaymentResourceNotFoundException;
import com.meterflow.payments.api.PaymentStatus;
import com.meterflow.payments.api.PaymentTransactionSummary;
import com.meterflow.shared.SupportedCurrency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST adapter for Payments & Dunning strictly over {@code payments :: api} (FR8, ADR-007, ADR-015).
 * Mock payment initiation and dunning worker controls are restricted strictly to ADMIN/OPERATOR routes.
 */
@RestController
class PaymentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentProcessingFacade paymentProcessingFacade;
    private final PaymentQueryFacade paymentQueryFacade;
    private final DunningManagementFacade dunningManagementFacade;
    private final DunningPolicyFacade dunningPolicyFacade;
    private final InvoiceManagementFacade invoiceManagementFacade;
    private final CustomerAccessGuard customerAccessGuard;

    PaymentController(
            PaymentProcessingFacade paymentProcessingFacade,
            PaymentQueryFacade paymentQueryFacade,
            DunningManagementFacade dunningManagementFacade,
            DunningPolicyFacade dunningPolicyFacade,
            InvoiceManagementFacade invoiceManagementFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.paymentProcessingFacade = paymentProcessingFacade;
        this.paymentQueryFacade = paymentQueryFacade;
        this.dunningManagementFacade = dunningManagementFacade;
        this.dunningPolicyFacade = dunningPolicyFacade;
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    // ------------------------------------------------------------------------
    // Operator / Admin Endpoints (/api/v1/payments/** & /api/v1/dunning/**)
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/payments")
    ResponseEntity<PaymentDetail> initiateMockPayment(@Valid @RequestBody InitiatePaymentRequest request) {
        PaymentDetail detail = paymentProcessingFacade.initiatePayment(new InitiatePaymentCommand(
                request.invoiceId(),
                request.amount(),
                request.currency(),
                request.simulationOutcome(),
                request.attemptSource(),
                request.idempotencyKey()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @GetMapping("/api/v1/payments")
    PageResponse<PaymentTransactionSummary> listPaymentsAdmin(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(paymentQueryFacade.listAllPayments(status, pageable));
    }

    @GetMapping("/api/v1/payments/{paymentId}")
    PaymentDetail getPaymentDetailAdmin(@PathVariable UUID paymentId) {
        return paymentQueryFacade.findPaymentById(paymentId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Payment not found: " + paymentId));
    }

    @GetMapping("/api/v1/payments/{paymentId}/attempts")
    List<PaymentAttemptRecord> listPaymentAttemptsAdmin(@PathVariable UUID paymentId) {
        return paymentQueryFacade.listPaymentAttempts(paymentId);
    }

    @GetMapping("/api/v1/payments/invoices/{invoiceId}")
    List<PaymentTransactionSummary> listInvoicePaymentsAdmin(@PathVariable UUID invoiceId) {
        return paymentQueryFacade.listPaymentsForInvoice(invoiceId);
    }

    @GetMapping("/api/v1/payments/customers/{customerId}")
    PageResponse<PaymentTransactionSummary> listCustomerPaymentsAdmin(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(paymentQueryFacade.listCustomerPayments(customerId, pageable));
    }

    @PostMapping("/api/v1/dunning/policies")
    ResponseEntity<DunningPolicySummary> configureDunningPolicy(
            @Valid @RequestBody ConfigureDunningPolicyRequest request
    ) {
        DunningPolicySummary created = dunningPolicyFacade.configurePolicy(new ConfigureDunningPolicyCommand(
                request.policyCode(),
                request.name(),
                request.retryOffsetsHours(),
                request.suspensionOffsetHours()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/dunning/policies")
    List<DunningPolicySummary> listDunningPolicies() {
        return dunningPolicyFacade.listPolicies();
    }

    @GetMapping("/api/v1/dunning/policies/active")
    DunningPolicySummary getActiveDunningPolicy() {
        return dunningPolicyFacade.getActivePolicy();
    }

    @GetMapping("/api/v1/dunning/cases")
    PageResponse<DunningCaseSummary> listDunningCasesAdmin(
            @RequestParam(required = false) DunningCaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(paymentQueryFacade.listDunningCases(status, pageable));
    }

    @GetMapping("/api/v1/dunning/cases/{dunningCaseId}")
    DunningCaseDetail getDunningCaseDetailAdmin(@PathVariable UUID dunningCaseId) {
        return paymentQueryFacade.findDunningCaseById(dunningCaseId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Dunning case not found: " + dunningCaseId));
    }

    @GetMapping("/api/v1/dunning/invoices/{invoiceId}")
    DunningCaseDetail getDunningCaseByInvoiceAdmin(@PathVariable UUID invoiceId) {
        return paymentQueryFacade.findDunningCaseByInvoiceId(invoiceId)
                .orElseThrow(() -> new PaymentResourceNotFoundException(
                        "Dunning case not found for invoice: " + invoiceId));
    }

    @PostMapping("/api/v1/dunning/process")
    DunningExecutionSummary processDueDunning(@RequestBody(required = false) ProcessDunningRequest request) {
        Instant refTime = request == null ? null : request.referenceTime();
        MockPaymentOutcome override = request == null ? null : request.retryOutcomeOverride();
        return dunningManagementFacade.processDueDunning(refTime, override);
    }

    // ------------------------------------------------------------------------
    // Customer-Scoped Read-Only Endpoints (Zero Simulation Controls Exposed)
    // ------------------------------------------------------------------------

    @GetMapping("/api/v1/customer/{customerId}/payments")
    PageResponse<PaymentTransactionSummary> listCustomerPayments(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(paymentQueryFacade.listCustomerPayments(customerId, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/payments/{paymentId}")
    PaymentDetail getCustomerPaymentDetail(
            @PathVariable UUID customerId,
            @PathVariable UUID paymentId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PaymentDetail detail = paymentQueryFacade.findPaymentById(paymentId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Payment not found: " + paymentId));
        if (!detail.payment().customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Payment does not belong to customer");
        }
        return detail;
    }

    @GetMapping("/api/v1/customer/{customerId}/payments/{paymentId}/attempts")
    List<PaymentAttemptRecord> listCustomerPaymentAttempts(
            @PathVariable UUID customerId,
            @PathVariable UUID paymentId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PaymentDetail detail = paymentQueryFacade.findPaymentById(paymentId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Payment not found: " + paymentId));
        if (!detail.payment().customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Payment does not belong to customer");
        }
        return detail.attempts();
    }

    @GetMapping("/api/v1/customer/{customerId}/payments/invoices/{invoiceId}")
    List<PaymentTransactionSummary> listCustomerInvoicePayments(
            @PathVariable UUID customerId,
            @PathVariable UUID invoiceId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        InvoiceSummary invoice = invoiceManagementFacade.findInvoiceById(invoiceId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Invoice not found: " + invoiceId));
        if (!invoice.customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Invoice does not belong to customer");
        }
        return paymentQueryFacade.listPaymentsForInvoice(invoiceId);
    }

    @GetMapping("/api/v1/customer/{customerId}/dunning/cases")
    PageResponse<DunningCaseSummary> listCustomerDunningCases(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(paymentQueryFacade.listCustomerDunningCases(customerId, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/dunning/cases/{dunningCaseId}")
    DunningCaseDetail getCustomerDunningCaseDetail(
            @PathVariable UUID customerId,
            @PathVariable UUID dunningCaseId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        DunningCaseDetail detail = paymentQueryFacade.findDunningCaseById(dunningCaseId)
                .orElseThrow(() -> new PaymentResourceNotFoundException("Dunning case not found: " + dunningCaseId));
        if (!detail.dunningCase().customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Dunning case does not belong to customer");
        }
        return detail;
    }

    record InitiatePaymentRequest(
            @NotNull UUID invoiceId,
            BigDecimal amount,
            SupportedCurrency currency,
            MockPaymentOutcome simulationOutcome,
            PaymentAttemptSource attemptSource,
            String idempotencyKey
    ) {
    }

    record ConfigureDunningPolicyRequest(
            @NotBlank String policyCode,
            @NotBlank String name,
            List<Integer> retryOffsetsHours,
            Integer suspensionOffsetHours
    ) {
    }

    record ProcessDunningRequest(
            Instant referenceTime,
            MockPaymentOutcome retryOutcomeOverride
    ) {
    }
}
