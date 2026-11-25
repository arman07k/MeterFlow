package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.CreditGrantType;
import com.meterflow.billing.api.CreditNoteDetail;
import com.meterflow.billing.api.CreditNoteFacade;
import com.meterflow.billing.api.CreditNoteIssuedEvent;
import com.meterflow.billing.api.CreditNoteLimitExceededException;
import com.meterflow.billing.api.CreditNoteLineInput;
import com.meterflow.billing.api.CreditNoteType;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.InvalidInvoiceTransitionException;
import com.meterflow.billing.api.IssueCreditGrantCommand;
import com.meterflow.billing.api.IssueCreditNoteCommand;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CreditNoteService implements CreditNoteFacade {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final CreditNoteLineRepository creditNoteLineRepository;
    private final PrepaidCreditService prepaidCreditService;
    private final ApplicationEventPublisher eventPublisher;
    private final BillingMetrics metrics;

    CreditNoteService(
            InvoiceRepository invoiceRepository,
            InvoiceLineRepository invoiceLineRepository,
            CreditNoteRepository creditNoteRepository,
            CreditNoteLineRepository creditNoteLineRepository,
            PrepaidCreditService prepaidCreditService,
            ApplicationEventPublisher eventPublisher,
            BillingMetrics metrics
    ) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.creditNoteLineRepository = creditNoteLineRepository;
        this.prepaidCreditService = prepaidCreditService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public CreditNoteDetail issueCreditNote(IssueCreditNoteCommand command) {
        if (command == null) {
            throw new InvalidBillingRequestException("Credit note command must not be null");
        }
        if (command.originalInvoiceId() == null) {
            throw new InvalidBillingRequestException("originalInvoiceId must not be null");
        }
        if (command.currency() == null) {
            throw new InvalidBillingRequestException("currency must not be null");
        }
        if (command.creditType() == null) {
            throw new InvalidBillingRequestException("creditType must not be null");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new InvalidBillingRequestException("reason must not be blank");
        }

        InvoiceEntity invoice = invoiceRepository.findByIdForUpdate(command.originalInvoiceId())
                .orElseThrow(() -> new BillingResourceNotFoundException(
                        "Invoice not found: " + command.originalInvoiceId()));

        String idempotencyKey = normalizeOptionalKey(command.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<CreditNoteEntity> replay = creditNoteRepository.findByCustomerIdAndIdempotencyKey(
                    invoice.getCustomerId(), idempotencyKey);
            if (replay.isPresent()) {
                return loadCreditNoteDetail(replay.get());
            }
        }

        if (!invoice.getStatus().isEligibleForCreditNote()) {
            throw new InvalidInvoiceTransitionException(
                    "Credit notes can only be issued against finalized invoices; current status=" + invoice.getStatus());
        }
        if (invoice.getCurrency() != command.currency()) {
            throw new CurrencyMismatchException(invoice.getCurrency(), command.currency());
        }

        SupportedCurrency currency = invoice.getCurrency();
        Money zero = Money.zero(currency).roundToPersisted();
        Money invoiceTotal = Money.of(invoice.getTotalAmount(), currency).roundToPersisted();
        Money alreadyCredited = Money.of(
                creditNoteRepository.sumTotalCreditByInvoiceId(invoice.getId()), currency).roundToPersisted();
        Money remainingCreditableTotal = invoiceTotal.subtract(alreadyCredited).roundToPersisted();

        if (!remainingCreditableTotal.isPositive()) {
            throw new CreditNoteLimitExceededException(
                    "Invoice " + invoice.getInvoiceNumber() + " has already been fully credited");
        }

        BigDecimal effectiveTaxRatio = BigDecimal.ZERO;
        if (invoice.getTaxableAmount().compareTo(BigDecimal.ZERO) > 0
                && invoice.getTaxAmount().compareTo(BigDecimal.ZERO) > 0) {
            effectiveTaxRatio = invoice.getTaxAmount().divide(
                    invoice.getTaxableAmount(), Money.INTERMEDIATE_SCALE, Money.ROUNDING_MODE);
        }

        List<InvoiceLineEntity> originalLines = invoiceLineRepository.findByInvoiceIdOrderByLineOrderAsc(invoice.getId());
        Set<UUID> validLineIds = new HashSet<>();
        for (InvoiceLineEntity l : originalLines) {
            validLineIds.add(l.getId());
        }

        Instant now = nowMicros();
        List<DraftCreditNoteLine> plannedLines = new ArrayList<>();
        Money subtotalCredit;
        Money taxCredit;
        Money totalCredit;

        if (command.creditType() == CreditNoteType.FULL) {
            if (alreadyCredited.isZero()) {
                subtotalCredit = Money.of(invoice.getSubtotalAmount(), currency).max(zero).roundToPersisted();
                taxCredit = Money.of(invoice.getTaxAmount(), currency).max(zero).roundToPersisted();
                totalCredit = invoiceTotal;

                Money allocatedSub = zero;
                Money allocatedTax = zero;
                List<InvoiceLineEntity> positiveLines = originalLines.stream()
                        .filter(l -> l.getLineAmount().compareTo(BigDecimal.ZERO) > 0)
                        .toList();

                if (!positiveLines.isEmpty() && subtotalCredit.isPositive()) {
                    for (int i = 0; i < positiveLines.size(); i++) {
                        InvoiceLineEntity orig = positiveLines.get(i);
                        boolean last = (i == positiveLines.size() - 1);
                        Money lineSub = last
                                ? subtotalCredit.subtract(allocatedSub).max(Money.of(orig.getLineAmount(), currency).min(subtotalCredit.subtract(allocatedSub))).roundToPersisted()
                                : Money.of(orig.getLineAmount(), currency).min(subtotalCredit.subtract(allocatedSub)).roundToPersisted();
                        if (!lineSub.isPositive()) {
                            continue;
                        }
                        Money lineTax = last
                                ? taxCredit.subtract(allocatedTax).max(zero).roundToPersisted()
                                : lineSub.multiply(effectiveTaxRatio).roundToPersisted().min(taxCredit.subtract(allocatedTax));
                        allocatedSub = allocatedSub.add(lineSub).roundToPersisted();
                        allocatedTax = allocatedTax.add(lineTax).roundToPersisted();
                        plannedLines.add(new DraftCreditNoteLine(orig.getId(), "Full credit: " + orig.getDescription(), lineSub, lineTax));
                    }
                }
                if (plannedLines.isEmpty()) {
                    plannedLines.add(new DraftCreditNoteLine(
                            null,
                            "Full credit note for invoice " + invoice.getInvoiceNumber(),
                            subtotalCredit.isPositive() ? subtotalCredit : totalCredit,
                            subtotalCredit.isPositive() ? taxCredit : zero
                    ));
                }
            } else {
                // Remaining full credit after an earlier partial credit note.
                BigDecimal divisor = BigDecimal.ONE.add(effectiveTaxRatio);
                subtotalCredit = remainingCreditableTotal.divide(divisor, Money.INTERMEDIATE_SCALE, Money.ROUNDING_MODE)
                        .roundToPersisted();
                taxCredit = remainingCreditableTotal.subtract(subtotalCredit).roundToPersisted();
                totalCredit = remainingCreditableTotal;
                plannedLines.add(new DraftCreditNoteLine(
                        null,
                        "Full remaining credit for invoice " + invoice.getInvoiceNumber(),
                        subtotalCredit,
                        taxCredit
                ));
            }
        } else {
            // PARTIAL credit note
            if (!command.lines().isEmpty()) {
                subtotalCredit = zero;
                taxCredit = zero;
                for (CreditNoteLineInput input : command.lines()) {
                    if (input == null || input.creditAmount() == null) {
                        throw new InvalidBillingRequestException("Credit note line amount must not be null");
                    }
                    if (input.originalInvoiceLineId() != null && !validLineIds.contains(input.originalInvoiceLineId())) {
                        throw new InvalidBillingRequestException(
                                "Invoice line " + input.originalInvoiceLineId()
                                        + " does not belong to invoice " + invoice.getId());
                    }
                    Money lineCredit = Money.of(input.creditAmount(), currency).roundToPersisted();
                    if (!lineCredit.isPositive()) {
                        throw new InvalidBillingRequestException("Credit note line amount must be positive");
                    }
                    Money lineTax = lineCredit.multiply(effectiveTaxRatio).roundToPersisted();
                    String desc = input.description() == null || input.description().isBlank()
                            ? "Partial credit adjustment"
                            : input.description().trim();
                    plannedLines.add(new DraftCreditNoteLine(input.originalInvoiceLineId(), desc, lineCredit, lineTax));
                    subtotalCredit = subtotalCredit.add(lineCredit).roundToPersisted();
                    taxCredit = taxCredit.add(lineTax).roundToPersisted();
                }
                totalCredit = subtotalCredit.add(taxCredit).roundToPersisted();
            } else if (command.subtotalCreditOverride() != null) {
                subtotalCredit = Money.of(command.subtotalCreditOverride(), currency).roundToPersisted();
                if (!subtotalCredit.isPositive()) {
                    throw new InvalidBillingRequestException("subtotalCreditOverride must be positive");
                }
                taxCredit = subtotalCredit.multiply(effectiveTaxRatio).roundToPersisted();
                totalCredit = subtotalCredit.add(taxCredit).roundToPersisted();
                plannedLines.add(new DraftCreditNoteLine(
                        null,
                        "Partial credit for invoice " + invoice.getInvoiceNumber(),
                        subtotalCredit,
                        taxCredit
                ));
            } else {
                throw new InvalidBillingRequestException(
                        "Partial credit note requires either line items or subtotalCreditOverride");
            }
        }

        if (!totalCredit.isPositive()) {
            throw new InvalidBillingRequestException("Total credit note amount must be positive");
        }
        if (alreadyCredited.add(totalCredit).isGreaterThan(invoiceTotal)) {
            throw new CreditNoteLimitExceededException(
                    "Credit note amount " + totalCredit + " exceeds remaining creditable balance "
                            + remainingCreditableTotal + " on invoice " + invoice.getInvoiceNumber());
        }

        Money currentDue = Money.of(invoice.getAmountDue(), currency).roundToPersisted();
        Money appliedToDue = currentDue.min(totalCredit).roundToPersisted();
        Money refundedToWallet = totalCredit.subtract(appliedToDue).roundToPersisted();
        Money newDue = currentDue.subtract(appliedToDue).roundToPersisted();
        Money newPayableDue = newDue.roundToPayable();

        invoice.applyCreditNoteReduction(appliedToDue.amount(), newDue.amount(), newPayableDue.amount(), now);
        invoiceRepository.saveAndFlush(invoice);

        long seq = creditNoteRepository.nextCreditNoteSequenceValue();
        String creditNoteNumber = "CN-" + Year.now(ZoneOffset.UTC).getValue() + "-" + seq;

        CreditNoteEntity creditNote = new CreditNoteEntity(
                creditNoteNumber,
                invoice.getId(),
                invoice.getCustomerId(),
                currency,
                command.creditType(),
                truncate(command.reason().trim(), 255),
                subtotalCredit.amount(),
                taxCredit.amount(),
                totalCredit.amount(),
                appliedToDue.amount(),
                refundedToWallet.amount(),
                idempotencyKey,
                now
        );
        CreditNoteEntity savedCn = creditNoteRepository.saveAndFlush(creditNote);

        List<CreditNoteLineEntity> lineEntities = new ArrayList<>();
        for (DraftCreditNoteLine planned : plannedLines) {
            lineEntities.add(new CreditNoteLineEntity(
                    savedCn.getId(),
                    planned.originalInvoiceLineId(),
                    truncate(planned.description(), 255),
                    planned.creditAmount().amount(),
                    planned.taxAmount().amount(),
                    currency,
                    now
            ));
        }
        List<CreditNoteLineEntity> savedLines = creditNoteLineRepository.saveAllAndFlush(lineEntities);

        if (refundedToWallet.isPositive()) {
            prepaidCreditService.issueCreditGrant(new IssueCreditGrantCommand(
                    invoice.getCustomerId(),
                    currency,
                    CreditGrantType.PREPAID,
                    "Credit wallet refund from credit note " + creditNoteNumber,
                    refundedToWallet.amount(),
                    50,
                    null,
                    "cn-refund:" + creditNoteNumber
            ));
        }

        eventPublisher.publishEvent(new CreditNoteIssuedEvent(
                savedCn.getId(),
                savedCn.getCreditNoteNumber(),
                savedCn.getOriginalInvoiceId(),
                savedCn.getCustomerId(),
                savedCn.getCurrency(),
                Money.of(savedCn.getTotalCredit(), currency),
                Money.of(savedCn.getAppliedToInvoiceDue(), currency),
                Money.of(savedCn.getRefundedToCreditWallet(), currency),
                savedCn.getIssuedAt()
        ));

        metrics.recordCreditNoteIssued();
        return BillingMapper.toCreditNoteDetail(savedCn, savedLines);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditNoteDetail> findCreditNoteById(UUID creditNoteId) {
        return creditNoteRepository.findById(creditNoteId).map(this::loadCreditNoteDetail);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditNoteDetail> listCreditNotesForInvoice(UUID invoiceId) {
        return creditNoteRepository.findByOriginalInvoiceIdOrderByIssuedAtDesc(invoiceId)
                .stream()
                .map(this::loadCreditNoteDetail)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CreditNoteDetail> listCustomerCreditNotes(UUID customerId, Pageable pageable) {
        return creditNoteRepository.findByCustomerIdOrderByIssuedAtDesc(customerId, pageable)
                .map(this::loadCreditNoteDetail);
    }

    private CreditNoteDetail loadCreditNoteDetail(CreditNoteEntity cn) {
        List<CreditNoteLineEntity> lines = creditNoteLineRepository.findByCreditNoteIdOrderByCreatedAtAsc(cn.getId());
        return BillingMapper.toCreditNoteDetail(cn, lines);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
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

    private record DraftCreditNoteLine(
            UUID originalInvoiceLineId,
            String description,
            Money creditAmount,
            Money taxAmount
    ) {
    }
}
