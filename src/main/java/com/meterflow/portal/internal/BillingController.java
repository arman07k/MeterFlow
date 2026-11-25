package com.meterflow.portal.internal;

import com.meterflow.billing.api.BillingAdjustmentFacade;
import com.meterflow.billing.api.BillingAdjustmentRecord;
import com.meterflow.billing.api.BillingAdjustmentType;
import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.BillingRunCommand;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.BillingRunSummary;
import com.meterflow.billing.api.ConfigureTaxPolicyCommand;
import com.meterflow.billing.api.CreateManualAdjustmentCommand;
import com.meterflow.billing.api.CreditGrantSummary;
import com.meterflow.billing.api.CreditGrantType;
import com.meterflow.billing.api.CreditNoteDetail;
import com.meterflow.billing.api.CreditNoteFacade;
import com.meterflow.billing.api.CreditNoteLineInput;
import com.meterflow.billing.api.CreditNoteType;
import com.meterflow.billing.api.CreditTransactionRecord;
import com.meterflow.billing.api.CustomerCreditBalance;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.HistoricalInvoiceComparisonResult;
import com.meterflow.billing.api.HistoricalInvoiceSimulationFacade;
import com.meterflow.billing.api.HistoricalInvoiceSimulationRequest;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.billing.api.IssueCreditGrantCommand;
import com.meterflow.billing.api.IssueCreditNoteCommand;
import com.meterflow.billing.api.LateUsageProcessingSummary;
import com.meterflow.billing.api.PrepaidCreditFacade;
import com.meterflow.billing.api.TaxPolicyFacade;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.billing.api.VoidInvoiceCommand;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
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
 * Thin REST controller exposing Billing, Invoicing, Tax Policies, Prepaid Credits, Credit Notes,
 * Billing Adjustments, and Batch Runs strictly over {@code billing :: api} (FR5, FR6, ADR-014).
 */
@RestController
class BillingController {

    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceManagementFacade invoiceManagementFacade;
    private final BillingRunFacade billingRunFacade;
    private final TaxPolicyFacade taxPolicyFacade;
    private final PrepaidCreditFacade prepaidCreditFacade;
    private final CreditNoteFacade creditNoteFacade;
    private final BillingAdjustmentFacade billingAdjustmentFacade;
    private final HistoricalInvoiceSimulationFacade historicalSimulationFacade;
    private final CustomerAccessGuard customerAccessGuard;

    BillingController(
            InvoiceManagementFacade invoiceManagementFacade,
            BillingRunFacade billingRunFacade,
            TaxPolicyFacade taxPolicyFacade,
            PrepaidCreditFacade prepaidCreditFacade,
            CreditNoteFacade creditNoteFacade,
            BillingAdjustmentFacade billingAdjustmentFacade,
            HistoricalInvoiceSimulationFacade historicalSimulationFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.invoiceManagementFacade = invoiceManagementFacade;
        this.billingRunFacade = billingRunFacade;
        this.taxPolicyFacade = taxPolicyFacade;
        this.prepaidCreditFacade = prepaidCreditFacade;
        this.creditNoteFacade = creditNoteFacade;
        this.billingAdjustmentFacade = billingAdjustmentFacade;
        this.historicalSimulationFacade = historicalSimulationFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    // ------------------------------------------------------------------------
    // Operator / Admin Endpoints (/api/v1/billing/**)
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/billing/tax-policies")
    ResponseEntity<TaxPolicySummary> configureTaxPolicy(@Valid @RequestBody ConfigureTaxPolicyRequest request) {
        TaxPolicySummary created = taxPolicyFacade.configureTaxPolicy(new ConfigureTaxPolicyCommand(
                request.policyCode(),
                request.name(),
                request.customerId(),
                request.jurisdiction(),
                request.taxName(),
                request.ratePercent(),
                Boolean.TRUE.equals(request.taxExempt())
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/billing/tax-policies")
    List<TaxPolicySummary> listTaxPolicies() {
        return taxPolicyFacade.listTaxPolicies();
    }

    @PostMapping("/api/v1/billing/invoices/draft")
    ResponseEntity<InvoiceDetail> generateOrRefreshDraft(@Valid @RequestBody GenerateDraftRequest request) {
        InvoiceDetail detail = invoiceManagementFacade.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                request.subscriptionId(),
                request.periodStart(),
                request.periodEnd(),
                request.gracePeriodHours(),
                null,
                request.idempotencyKey()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @PostMapping("/api/v1/billing/invoices/{invoiceId}/finalize")
    InvoiceDetail finalizeInvoice(
            @PathVariable UUID invoiceId,
            @RequestBody(required = false) FinalizeInvoiceRequest request
    ) {
        boolean applyCredits = request == null || request.applyPrepaidCredits() == null || request.applyPrepaidCredits();
        Instant dueAt = request == null ? null : request.dueAt();
        String key = request == null ? null : request.idempotencyKey();
        return invoiceManagementFacade.finalizeInvoice(invoiceId, new FinalizeInvoiceCommand(applyCredits, dueAt, key));
    }

    @PostMapping("/api/v1/billing/invoices/{invoiceId}/void")
    InvoiceDetail voidInvoice(
            @PathVariable UUID invoiceId,
            @RequestBody(required = false) VoidInvoiceRequest request
    ) {
        String reason = request == null ? null : request.reason();
        String key = request == null ? null : request.idempotencyKey();
        return invoiceManagementFacade.voidInvoice(invoiceId, new VoidInvoiceCommand(reason, key));
    }

    @GetMapping("/api/v1/billing/invoices")
    PageResponse<InvoiceSummary> listInvoicesAdmin(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(invoiceManagementFacade.listInvoices(status, pageable));
    }

    @GetMapping("/api/v1/billing/invoices/{invoiceId}")
    InvoiceDetail getInvoiceDetailAdmin(@PathVariable UUID invoiceId) {
        return invoiceManagementFacade.getInvoiceDetail(invoiceId);
    }

    @PostMapping("/api/v1/billing/runs/draft")
    ResponseEntity<BillingRunSummary> runDraftBatch(@RequestBody(required = false) TriggerBillingRunRequest request) {
        BillingRunSummary summary = billingRunFacade.runDraftGenerationBatch(toBillingRunCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @PostMapping("/api/v1/billing/runs/finalize")
    ResponseEntity<BillingRunSummary> runFinalizationBatch(
            @RequestBody(required = false) TriggerBillingRunRequest request
    ) {
        BillingRunSummary summary = billingRunFacade.runFinalizationBatch(toBillingRunCommand(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(summary);
    }

    @GetMapping("/api/v1/billing/runs")
    PageResponse<BillingRunSummary> listBillingRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(billingRunFacade.listBillingRuns(pageable));
    }

    @GetMapping("/api/v1/billing/runs/{billingRunId}")
    BillingRunSummary getBillingRun(@PathVariable UUID billingRunId) {
        return billingRunFacade.findBillingRunById(billingRunId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Billing run not found: " + billingRunId));
    }

    @PostMapping("/api/v1/billing/runs/{billingRunId}/resume")
    BillingRunSummary resumeFailedBillingRun(@PathVariable UUID billingRunId) {
        return billingRunFacade.resumeFailedBillingRun(billingRunId);
    }

    @PostMapping("/api/v1/billing/credits/grants")
    ResponseEntity<CreditGrantSummary> issueCreditGrant(@Valid @RequestBody IssueCreditGrantRequest request) {
        CreditGrantSummary grant = prepaidCreditFacade.issueCreditGrant(new IssueCreditGrantCommand(
                request.customerId(),
                request.currency(),
                request.grantType(),
                request.description(),
                request.amount(),
                request.priority(),
                request.expiresAt(),
                request.idempotencyKey()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(grant);
    }

    @GetMapping("/api/v1/billing/customers/{customerId}/credits/balance")
    CustomerCreditBalance getCustomerCreditBalanceAdmin(
            @PathVariable UUID customerId,
            @RequestParam(required = false) SupportedCurrency currency
    ) {
        return prepaidCreditFacade.getCustomerCreditBalance(customerId, currency);
    }

    @GetMapping("/api/v1/billing/customers/{customerId}/credits/grants")
    PageResponse<CreditGrantSummary> listCustomerCreditGrantsAdmin(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(prepaidCreditFacade.listCustomerCreditGrants(customerId, pageable));
    }

    @GetMapping("/api/v1/billing/customers/{customerId}/credits/transactions")
    PageResponse<CreditTransactionRecord> listCustomerCreditTransactionsAdmin(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(prepaidCreditFacade.listCustomerCreditTransactions(customerId, pageable));
    }

    @PostMapping("/api/v1/billing/credit-notes")
    ResponseEntity<CreditNoteDetail> issueCreditNote(@Valid @RequestBody IssueCreditNoteRequest request) {
        CreditNoteDetail detail = creditNoteFacade.issueCreditNote(new IssueCreditNoteCommand(
                request.originalInvoiceId(),
                request.currency(),
                request.creditType(),
                request.reason(),
                request.subtotalCreditOverride(),
                request.lines(),
                request.idempotencyKey()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(detail);
    }

    @GetMapping("/api/v1/billing/credit-notes/{creditNoteId}")
    CreditNoteDetail getCreditNoteAdmin(@PathVariable UUID creditNoteId) {
        return creditNoteFacade.findCreditNoteById(creditNoteId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Credit note not found: " + creditNoteId));
    }

    @GetMapping("/api/v1/billing/invoices/{invoiceId}/credit-notes")
    List<CreditNoteDetail> listInvoiceCreditNotesAdmin(@PathVariable UUID invoiceId) {
        return creditNoteFacade.listCreditNotesForInvoice(invoiceId);
    }

    @PostMapping("/api/v1/billing/adjustments/late-usage/process")
    LateUsageProcessingSummary processLateUsage(@RequestParam(required = false) UUID customerId) {
        return billingAdjustmentFacade.processPendingLateUsage(customerId);
    }

    @PostMapping("/api/v1/billing/adjustments/manual")
    ResponseEntity<BillingAdjustmentRecord> createManualAdjustment(
            @Valid @RequestBody CreateManualAdjustmentRequest request
    ) {
        BillingAdjustmentRecord created = billingAdjustmentFacade.createManualAdjustment(
                new CreateManualAdjustmentCommand(
                        request.customerId(),
                        request.subscriptionId(),
                        request.originalInvoiceId(),
                        request.adjustmentType(),
                        request.description(),
                        request.amount(),
                        request.currency(),
                        request.idempotencyKey()
                ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/billing/customers/{customerId}/adjustments")
    PageResponse<BillingAdjustmentRecord> listCustomerAdjustmentsAdmin(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(billingAdjustmentFacade.listCustomerAdjustments(customerId, pageable));
    }

    // ------------------------------------------------------------------------
    // Customer-Scoped Endpoints (/api/v1/customer/{customerId}/billing/**)
    // ------------------------------------------------------------------------

    @GetMapping("/api/v1/customer/{customerId}/billing/invoices")
    PageResponse<InvoiceSummary> listCustomerInvoices(
            @PathVariable UUID customerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(invoiceManagementFacade.listCustomerInvoices(customerId, status, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/invoices/{invoiceId}")
    InvoiceDetail getCustomerInvoiceDetail(
            @PathVariable UUID customerId,
            @PathVariable UUID invoiceId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        InvoiceDetail detail = invoiceManagementFacade.getInvoiceDetail(invoiceId);
        if (!detail.invoice().customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Invoice does not belong to customer");
        }
        return detail;
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/credits/balance")
    CustomerCreditBalance getCustomerCreditBalance(
            @PathVariable UUID customerId,
            @RequestParam(required = false) SupportedCurrency currency
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        return prepaidCreditFacade.getCustomerCreditBalance(customerId, currency);
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/credits/grants")
    PageResponse<CreditGrantSummary> listCustomerCreditGrants(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(prepaidCreditFacade.listCustomerCreditGrants(customerId, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/credits/transactions")
    PageResponse<CreditTransactionRecord> listCustomerCreditTransactions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(prepaidCreditFacade.listCustomerCreditTransactions(customerId, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/credit-notes")
    PageResponse<CreditNoteDetail> listCustomerCreditNotes(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(creditNoteFacade.listCustomerCreditNotes(customerId, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/credit-notes/{creditNoteId}")
    CreditNoteDetail getCustomerCreditNote(
            @PathVariable UUID customerId,
            @PathVariable UUID creditNoteId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        CreditNoteDetail detail = creditNoteFacade.findCreditNoteById(creditNoteId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Credit note not found: " + creditNoteId));
        if (!detail.customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Credit note does not belong to customer");
        }
        return detail;
    }

    @GetMapping("/api/v1/customer/{customerId}/billing/adjustments")
    PageResponse<BillingAdjustmentRecord> listCustomerAdjustments(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(billingAdjustmentFacade.listCustomerAdjustments(customerId, pageable));
    }

    @PostMapping("/api/v1/billing/simulations/historical")
    HistoricalInvoiceComparisonResult simulateHistoricalInvoiceAdmin(
            @Valid @RequestBody HistoricalSimulationApiRequest request
    ) {
        return historicalSimulationFacade.simulateHistoricalInvoice(new HistoricalInvoiceSimulationRequest(
                request.originalInvoiceId(),
                request.customerId(),
                request.subscriptionId(),
                request.periodStart(),
                request.periodEnd(),
                request.targetPlanVersionId(),
                request.seatQuantityOverride()
        ));
    }

    @PostMapping("/api/v1/customer/{customerId}/billing/simulations/historical")
    HistoricalInvoiceComparisonResult simulateCustomerHistoricalInvoice(
            @PathVariable UUID customerId,
            @Valid @RequestBody HistoricalSimulationApiRequest request
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        return historicalSimulationFacade.simulateHistoricalInvoice(new HistoricalInvoiceSimulationRequest(
                request.originalInvoiceId(),
                customerId,
                request.subscriptionId(),
                request.periodStart(),
                request.periodEnd(),
                request.targetPlanVersionId(),
                request.seatQuantityOverride()
        ));
    }

    private static BillingRunCommand toBillingRunCommand(TriggerBillingRunRequest request) {
        if (request == null) {
            return new BillingRunCommand(null, null, null, null);
        }
        return new BillingRunCommand(
                request.customerId(),
                request.cutoffTimestamp(),
                request.gracePeriodHours(),
                request.idempotencyKey()
        );
    }

    record ConfigureTaxPolicyRequest(
            @NotBlank String policyCode,
            @NotBlank String name,
            UUID customerId,
            @NotBlank String jurisdiction,
            @NotBlank String taxName,
            @NotNull BigDecimal ratePercent,
            Boolean taxExempt
    ) {
    }

    record GenerateDraftRequest(
            @NotNull UUID subscriptionId,
            Instant periodStart,
            Instant periodEnd,
            Integer gracePeriodHours,
            String idempotencyKey
    ) {
    }

    record FinalizeInvoiceRequest(
            Boolean applyPrepaidCredits,
            Instant dueAt,
            String idempotencyKey
    ) {
    }

    record VoidInvoiceRequest(
            String reason,
            String idempotencyKey
    ) {
    }

    record TriggerBillingRunRequest(
            UUID customerId,
            Instant cutoffTimestamp,
            Integer gracePeriodHours,
            String idempotencyKey
    ) {
    }

    record IssueCreditGrantRequest(
            @NotNull UUID customerId,
            @NotNull SupportedCurrency currency,
            @NotNull CreditGrantType grantType,
            @NotBlank String description,
            @NotNull BigDecimal amount,
            Integer priority,
            Instant expiresAt,
            String idempotencyKey
    ) {
    }

    record IssueCreditNoteRequest(
            @NotNull UUID originalInvoiceId,
            @NotNull SupportedCurrency currency,
            @NotNull CreditNoteType creditType,
            @NotBlank String reason,
            BigDecimal subtotalCreditOverride,
            List<CreditNoteLineInput> lines,
            String idempotencyKey
    ) {
    }

    record CreateManualAdjustmentRequest(
            @NotNull UUID customerId,
            @NotNull UUID subscriptionId,
            UUID originalInvoiceId,
            @NotNull BillingAdjustmentType adjustmentType,
            @NotBlank String description,
            @NotNull BigDecimal amount,
            @NotNull SupportedCurrency currency,
            String idempotencyKey
    ) {
    }

    record HistoricalSimulationApiRequest(
            UUID originalInvoiceId,
            UUID customerId,
            UUID subscriptionId,
            Instant periodStart,
            Instant periodEnd,
            @NotNull UUID targetPlanVersionId,
            Integer seatQuantityOverride
    ) {
    }
}
