package com.meterflow.payments.internal;

import com.meterflow.payments.api.ConfigureDunningPolicyCommand;
import com.meterflow.payments.api.DunningPolicyFacade;
import com.meterflow.payments.api.DunningPolicySummary;
import com.meterflow.payments.api.InvalidPaymentRequestException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DunningPolicyService implements DunningPolicyFacade {

    private final DunningPolicyRepository policyRepository;
    private final Clock clock;

    DunningPolicyService(DunningPolicyRepository policyRepository, Clock clock) {
        this.policyRepository = policyRepository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DunningPolicySummary configurePolicy(ConfigureDunningPolicyCommand command) {
        if (command == null) {
            throw new InvalidPaymentRequestException("Dunning policy command must not be null");
        }
        if (command.policyCode() == null || command.policyCode().isBlank()) {
            throw new InvalidPaymentRequestException("policyCode must not be blank");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new InvalidPaymentRequestException("name must not be blank");
        }
        String code = command.policyCode().trim();
        if (policyRepository.findByPolicyCode(code).isPresent()) {
            throw new InvalidPaymentRequestException("Dunning policy code already exists: " + code);
        }

        List<Integer> offsets = command.retryOffsetsHours().isEmpty()
                ? DunningScheduleCalculator.DEFAULT_RETRY_OFFSETS_HOURS
                : command.retryOffsetsHours();
        int suspensionOffset = command.suspensionOffsetHours() == null
                ? DunningScheduleCalculator.DEFAULT_SUSPENSION_OFFSET_HOURS
                : command.suspensionOffsetHours();
        DunningScheduleCalculator.validateSchedule(offsets, suspensionOffset);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        policyRepository.findFirstByActiveTrue().ifPresent(existing -> {
            existing.deactivate(now);
            policyRepository.saveAndFlush(existing);
        });

        DunningPolicyEntity created = new DunningPolicyEntity(code, command.name().trim(), offsets, suspensionOffset, now);
        return PaymentMapper.toPolicySummary(policyRepository.saveAndFlush(created));
    }

    @Override
    @Transactional
    public DunningPolicySummary getActivePolicy() {
        return PaymentMapper.toPolicySummary(requireActivePolicyEntity());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DunningPolicySummary> listPolicies() {
        return policyRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(PaymentMapper::toPolicySummary)
                .toList();
    }

    DunningPolicyEntity requireActivePolicyEntity() {
        return policyRepository.findFirstByActiveTrue()
                .orElseGet(() -> {
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    DunningPolicyEntity fallback = new DunningPolicyEntity(
                            "DEFAULT_DAY_1_3_7",
                            "Default Day 1 / Day 3 / Day 7 Dunning Policy",
                            DunningScheduleCalculator.DEFAULT_RETRY_OFFSETS_HOURS,
                            DunningScheduleCalculator.DEFAULT_SUSPENSION_OFFSET_HOURS,
                            now
                    );
                    return policyRepository.saveAndFlush(fallback);
                });
    }
}
