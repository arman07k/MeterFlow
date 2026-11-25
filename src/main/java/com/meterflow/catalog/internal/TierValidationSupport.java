package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Shared continuous tier boundary validation for GRADUATED_TIERS and VOLUME_TIERS (ADR-011).
 */
final class TierValidationSupport {

    private TierValidationSupport() {
    }

    static List<ChargeTierDefinition> validateAndSortTiers(List<ChargeTierDefinition> rawTiers) {
        if (rawTiers == null || rawTiers.isEmpty()) {
            throw new InvalidCatalogConfigurationException("Tiered charges require at least one tier");
        }
        List<ChargeTierDefinition> sorted = rawTiers.stream()
                .sorted(Comparator.comparingInt(ChargeTierDefinition::tierOrder))
                .toList();

        BigDecimal expectedLower = BigDecimal.ZERO;
        for (int i = 0; i < sorted.size(); i++) {
            ChargeTierDefinition tier = sorted.get(i);
            int expectedOrder = i + 1;
            if (tier.tierOrder() != expectedOrder) {
                throw new InvalidCatalogConfigurationException(
                        "Tier order must be sequential starting at 1; expected " + expectedOrder + " but got " + tier.tierOrder()
                );
            }
            if (tier.lowerBound() == null || tier.lowerBound().compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidCatalogConfigurationException("Tier lowerBound must be non-null and >= 0");
            }
            if (tier.unitPrice() == null || tier.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidCatalogConfigurationException("Tier unitPrice must be non-null and >= 0");
            }
            if (tier.flatFee() != null && tier.flatFee().compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidCatalogConfigurationException("Tier flatFee must be >= 0");
            }
            if (i == 0 && tier.lowerBound().compareTo(BigDecimal.ZERO) != 0) {
                throw new InvalidCatalogConfigurationException("First tier must start at lowerBound = 0");
            }
            if (tier.lowerBound().compareTo(expectedLower) != 0) {
                throw new InvalidCatalogConfigurationException(
                        "Tiers must be continuous without gaps or overlaps: expected lowerBound "
                                + expectedLower + " at tier " + tier.tierOrder() + " but got " + tier.lowerBound()
                );
            }
            boolean isLast = (i == sorted.size() - 1);
            if (tier.isUnbounded()) {
                if (!isLast) {
                    throw new InvalidCatalogConfigurationException("Only the final tier may be unbounded");
                }
            } else {
                if (tier.lowerBound().compareTo(tier.upperBound()) >= 0) {
                    throw new InvalidCatalogConfigurationException(
                            "Tier lowerBound must be strictly less than upperBound at tier " + tier.tierOrder()
                    );
                }
                expectedLower = tier.upperBound();
            }
        }
        return sorted;
    }
}
