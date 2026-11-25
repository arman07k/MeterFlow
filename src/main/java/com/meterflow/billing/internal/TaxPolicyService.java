package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.ConfigureTaxPolicyCommand;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.TaxPolicyFacade;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.identity.api.IdentityQueryFacade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxPolicyService implements TaxPolicyFacade {

    private static final BigDecimal MAX_TAX_RATE = BigDecimal.valueOf(100);

    private final TaxPolicyRepository taxPolicyRepository;
    private final IdentityQueryFacade identityQueryFacade;

    TaxPolicyService(TaxPolicyRepository taxPolicyRepository, IdentityQueryFacade identityQueryFacade) {
        this.taxPolicyRepository = taxPolicyRepository;
        this.identityQueryFacade = identityQueryFacade;
    }

    @Override
    @Transactional
    public TaxPolicySummary configureTaxPolicy(ConfigureTaxPolicyCommand command) {
        if (command == null) {
            throw new InvalidBillingRequestException("Tax policy configuration command must not be null");
        }
        String policyCode = requireNonBlank(command.policyCode(), "policyCode");
        String name = requireNonBlank(command.name(), "name");
        String jurisdiction = requireNonBlank(command.jurisdiction(), "jurisdiction");
        String taxName = requireNonBlank(command.taxName(), "taxName");

        BigDecimal ratePercent = command.ratePercent();
        if (ratePercent == null) {
            throw new InvalidBillingRequestException("ratePercent must not be null");
        }
        if (ratePercent.compareTo(BigDecimal.ZERO) < 0 || ratePercent.compareTo(MAX_TAX_RATE) > 0) {
            throw new InvalidBillingRequestException("ratePercent must be between 0 and 100");
        }
        BigDecimal normalizedRate = command.taxExempt()
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : ratePercent.setScale(4, RoundingMode.HALF_UP);

        if (taxPolicyRepository.findByPolicyCode(policyCode).isPresent()) {
            throw new InvalidBillingRequestException("Tax policy code already exists: " + policyCode);
        }

        if (command.customerId() != null) {
            identityQueryFacade.findCustomerById(command.customerId())
                    .orElseThrow(() -> new BillingResourceNotFoundException(
                            "Customer not found: " + command.customerId()));
        }

        Instant now = nowMicros();
        if (command.customerId() != null) {
            taxPolicyRepository.findFirstByCustomerIdAndActiveTrue(command.customerId())
                    .ifPresent(existing -> {
                        existing.deactivate(now);
                        taxPolicyRepository.saveAndFlush(existing);
                    });
        } else {
            taxPolicyRepository.findFirstByCustomerIdIsNullAndActiveTrue()
                    .ifPresent(existing -> {
                        existing.deactivate(now);
                        taxPolicyRepository.saveAndFlush(existing);
                    });
        }

        TaxPolicyEntity created = new TaxPolicyEntity(
                policyCode,
                name,
                command.customerId(),
                jurisdiction,
                taxName,
                normalizedRate,
                command.taxExempt(),
                now
        );
        return BillingMapper.toTaxPolicySummary(taxPolicyRepository.saveAndFlush(created));
    }

    @Override
    @Transactional(readOnly = true)
    public TaxPolicySummary resolveApplicableTaxPolicy(UUID customerId) {
        if (customerId != null) {
            var customerPolicy = taxPolicyRepository.findFirstByCustomerIdAndActiveTrue(customerId);
            if (customerPolicy.isPresent()) {
                return BillingMapper.toTaxPolicySummary(customerPolicy.get());
            }
        }
        return taxPolicyRepository.findFirstByCustomerIdIsNullAndActiveTrue()
                .map(BillingMapper::toTaxPolicySummary)
                .orElseGet(() -> {
                    Instant now = nowMicros();
                    return new TaxPolicySummary(
                            null,
                            "NO_TAX",
                            "Default Zero Tax",
                            null,
                            "DEFAULT",
                            "No Tax",
                            BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                            false,
                            true,
                            now,
                            now
                    );
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxPolicySummary> listTaxPolicies() {
        return taxPolicyRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(BillingMapper::toTaxPolicySummary)
                .toList();
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidBillingRequestException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
