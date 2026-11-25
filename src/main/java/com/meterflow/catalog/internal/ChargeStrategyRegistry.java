package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.ChargeCalculationStrategy;
import com.meterflow.catalog.api.UnsupportedChargeTypeException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Open/Closed registry resolving registered {@link ChargeCalculationStrategy} beans by charge type
 * without central switch statements (ADR-001, ADR-011).
 */
@Component
class ChargeStrategyRegistry {

    private final Map<String, ChargeCalculationStrategy> strategies;

    ChargeStrategyRegistry(List<ChargeCalculationStrategy> strategyBeans) {
        Map<String, ChargeCalculationStrategy> map = new LinkedHashMap<>();
        for (ChargeCalculationStrategy strategy : strategyBeans) {
            String key = normalizeKey(strategy.supportedChargeType());
            if (map.containsKey(key)) {
                throw new IllegalStateException("Duplicate ChargeCalculationStrategy registered for type: " + key);
            }
            map.put(key, strategy);
        }
        this.strategies = Collections.unmodifiableMap(map);
    }

    ChargeCalculationStrategy resolve(String chargeType) {
        if (chargeType == null || chargeType.isBlank()) {
            throw new UnsupportedChargeTypeException(String.valueOf(chargeType));
        }
        String key = normalizeKey(chargeType);
        ChargeCalculationStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new UnsupportedChargeTypeException(chargeType);
        }
        return strategy;
    }

    Set<String> registeredChargeTypes() {
        return strategies.keySet();
    }

    private static String normalizeKey(String chargeType) {
        return chargeType.trim().toUpperCase(Locale.ROOT);
    }
}
