package com.meterflow.catalog.api;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, stateless FR2 metric filter and aggregation-semantic evaluator shared by Catalogue
 * validation tests and future Metering runtime aggregation (zero duplicated filter/property logic).
 *
 * <p>Handles missing, null, and non-numeric values explicitly—never silently coercing invalid
 * numeric properties to zero. Defines deterministic LATEST_VALUE tie-breaking by
 * {@code (eventTimestamp DESC, ingestedAt DESC, eventId DESC)}.
 */
public final class MetricEvaluationSupport {

    public static final Comparator<MetricEventSample> LATEST_VALUE_ORDER = Comparator
            .comparing(MetricEventSample::eventTimestamp, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(MetricEventSample::ingestedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(
                    sample -> sample.eventId() == null ? null : sample.eventId().toString(),
                    Comparator.nullsLast(Comparator.reverseOrder())
            );

    private MetricEvaluationSupport() {
    }

    public static boolean matchesAllFilters(
            List<MetricFilterDefinition> filters,
            Map<String, Object> properties
    ) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Map<String, Object> safeProps = properties == null ? Map.of() : properties;
        for (MetricFilterDefinition filter : filters) {
            if (!matchesFilter(filter, safeProps)) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchesFilter(MetricFilterDefinition filter, Map<String, Object> properties) {
        Objects.requireNonNull(filter, "filter must not be null");
        Map<String, Object> safeProps = properties == null ? Map.of() : properties;
        boolean hasKey = safeProps.containsKey(filter.propertyKey());
        Object rawValue = safeProps.get(filter.propertyKey());
        String stringValue = rawValue == null ? null : String.valueOf(rawValue);

        return switch (filter.operator()) {
            case EXISTS -> hasKey && rawValue != null;
            case EQUALS -> rawValue != null && !filter.values().isEmpty()
                    && filter.values().getFirst().equals(stringValue);
            case NOT_EQUALS -> rawValue != null && !filter.values().isEmpty()
                    && !filter.values().getFirst().equals(stringValue);
            case IN -> rawValue != null && filter.values().contains(stringValue);
            case NOT_IN -> rawValue != null && !filter.values().contains(stringValue);
        };
    }

    public static BigDecimal extractRequiredNumericProperty(
            BillableMetricDefinition metric,
            MetricEventSample sample
    ) {
        String propKey = metric.targetProperty();
        if (propKey == null || propKey.isBlank()) {
            throw new InvalidCatalogConfigurationException(
                    "Metric '" + metric.metricKey() + "' requires a non-blank targetProperty"
            );
        }
        Map<String, Object> props = sample.properties();
        if (!props.containsKey(propKey)) {
            throw new InvalidRatingContextException(
                    "Missing required numeric property '" + propKey + "' for metric '" + metric.metricKey() + "'"
            );
        }
        Object rawValue = props.get(propKey);
        if (rawValue == null) {
            throw new InvalidRatingContextException(
                    "Null numeric property '" + propKey + "' for metric '" + metric.metricKey() + "'"
            );
        }
        if (rawValue instanceof BigDecimal bd) {
            return bd;
        }
        if (rawValue instanceof Number num) {
            if (rawValue instanceof Double d && (d.isNaN() || d.isInfinite())) {
                throw new InvalidRatingContextException(
                        "Non-finite numeric value for property '" + propKey + "'"
                );
            }
            if (rawValue instanceof Float f && (f.isNaN() || f.isInfinite())) {
                throw new InvalidRatingContextException(
                        "Non-finite numeric value for property '" + propKey + "'"
                );
            }
            return new BigDecimal(num.toString());
        }
        if (rawValue instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                throw new InvalidRatingContextException(
                        "Blank numeric property '" + propKey + "' for metric '" + metric.metricKey() + "'"
                );
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException ex) {
                throw new InvalidRatingContextException(
                        "Invalid non-numeric value '" + str + "' for property '" + propKey + "'"
                );
            }
        }
        throw new InvalidRatingContextException(
                "Unsupported property type for numeric metric property '" + propKey + "'"
        );
    }

    public static String extractRequiredDistinctKey(
            BillableMetricDefinition metric,
            MetricEventSample sample
    ) {
        String propKey = metric.targetProperty();
        Map<String, Object> props = sample.properties();
        if (!props.containsKey(propKey) || props.get(propKey) == null) {
            throw new InvalidRatingContextException(
                    "Missing or null distinct target property '" + propKey + "' for metric '" + metric.metricKey() + "'"
            );
        }
        String value = String.valueOf(props.get(propKey));
        if (value.isBlank()) {
            throw new InvalidRatingContextException(
                    "Blank distinct target property '" + propKey + "' for metric '" + metric.metricKey() + "'"
            );
        }
        return value;
    }

    /**
     * Pure reference evaluation of all five FR2 aggregation types over a provided sample list.
     * Used for deterministic specification tests and reusable by future Metering evaluations.
     */
    public static BigDecimal evaluateSamples(
            BillableMetricDefinition metric,
            List<MetricEventSample> samples
    ) {
        Objects.requireNonNull(metric, "metric must not be null");
        if (samples == null || samples.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<MetricEventSample> matching = samples.stream()
                .filter(sample -> matchesAllFilters(metric.filters(), sample.properties()))
                .toList();

        if (matching.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return switch (metric.aggregationType()) {
            case COUNT -> BigDecimal.valueOf(matching.size());
            case SUM -> {
                BigDecimal sum = BigDecimal.ZERO;
                for (MetricEventSample sample : matching) {
                    sum = sum.add(extractRequiredNumericProperty(metric, sample));
                }
                yield sum;
            }
            case MAXIMUM -> {
                BigDecimal max = null;
                for (MetricEventSample sample : matching) {
                    BigDecimal value = extractRequiredNumericProperty(metric, sample);
                    if (max == null || value.compareTo(max) > 0) {
                        max = value;
                    }
                }
                yield max == null ? BigDecimal.ZERO : max;
            }
            case UNIQUE_COUNT -> {
                Set<String> distinct = new HashSet<>();
                for (MetricEventSample sample : matching) {
                    distinct.add(extractRequiredDistinctKey(metric, sample));
                }
                yield BigDecimal.valueOf(distinct.size());
            }
            case LATEST_VALUE -> {
                MetricEventSample latest = matching.stream()
                        .min(LATEST_VALUE_ORDER)
                        .orElseThrow();
                yield extractRequiredNumericProperty(metric, latest);
            }
        };
    }
}
