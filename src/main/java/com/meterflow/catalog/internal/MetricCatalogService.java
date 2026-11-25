package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.DuplicateCatalogKeyException;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.catalog.api.MetricFilterOperator;
import com.meterflow.catalog.api.UpdateMetricCommand;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MetricCatalogService implements MetricCatalogFacade {

    private final BillableMetricRepository metricRepository;
    private final MetricFilterRepository filterRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final CatalogCacheHelper cacheHelper;
    private final Clock clock;

    MetricCatalogService(
            BillableMetricRepository metricRepository,
            MetricFilterRepository filterRepository,
            IdentityQueryFacade identityQueryFacade,
            CatalogCacheHelper cacheHelper,
            Clock clock
    ) {
        this.metricRepository = metricRepository;
        this.filterRepository = filterRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.cacheHelper = cacheHelper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BillableMetricDefinition createMetric(CreateMetricCommand command) {
        if (command == null) {
            throw new InvalidCatalogConfigurationException("CreateMetricCommand must not be null");
        }
        String normalizedKey = normalizeKey(command.metricKey());
        String name = requireNonBlank(command.name(), "Metric name must not be blank", 160);
        if (command.aggregationType() == null) {
            throw new InvalidCatalogConfigurationException("Metric aggregationType must not be null");
        }
        String targetProp = validateTargetProperty(command.aggregationType(), command.targetProperty());
        List<MetricFilterDefinition> validatedFilters = validateFilters(command.filters());

        UUID customerId = command.customerId();
        if (customerId != null && identityQueryFacade.findCustomerById(customerId).isEmpty()) {
            throw new IdentityResourceNotFoundException("Customer not found for metric ownership");
        }

        boolean exists = customerId == null
                ? metricRepository.existsByMetricKeyAndCustomerIdIsNull(normalizedKey)
                : metricRepository.existsByMetricKeyAndCustomerId(normalizedKey, customerId);
        if (exists) {
            throw new DuplicateCatalogKeyException("Billable metric key already exists in this scope");
        }

        Instant now = nowUtc();
        BillableMetricEntity entity = new BillableMetricEntity(
                normalizedKey,
                name,
                trimNullable(command.description()),
                command.aggregationType(),
                targetProp,
                customerId,
                now
        );

        BillableMetricEntity saved;
        try {
            saved = metricRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateCatalogKeyException("Billable metric key already exists in this scope");
        }

        List<MetricFilterEntity> savedFilters = persistFilters(saved, validatedFilters, now);
        return toDefinition(saved, savedFilters);
    }

    @Override
    @Transactional
    public BillableMetricDefinition updateMetric(UUID metricId, UpdateMetricCommand command) {
        if (metricId == null || command == null) {
            throw new InvalidCatalogConfigurationException("metricId and UpdateMetricCommand must not be null");
        }
        BillableMetricEntity entity = metricRepository.findById(metricId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Billable metric not found"));

        String newName = command.name() != null
                ? requireNonBlank(command.name(), "Metric name must not be blank", 160)
                : entity.getName();
        String newDescription = command.description() != null
                ? trimNullable(command.description())
                : entity.getDescription();
        String newTargetProp = command.targetProperty() != null
                ? validateTargetProperty(entity.getAggregationType(), command.targetProperty())
                : entity.getTargetProperty();
        boolean newActive = command.active() != null ? command.active() : entity.isActive();

        Instant now = nowUtc();
        entity.update(newName, newDescription, newTargetProp, newActive, now);
        BillableMetricEntity saved = metricRepository.saveAndFlush(entity);

        List<MetricFilterEntity> filters;
        if (command.filters() != null) {
            List<MetricFilterDefinition> validatedFilters = validateFilters(command.filters());
            filterRepository.deleteByMetricId(metricId);
            filterRepository.flush();
            filters = persistFilters(saved, validatedFilters, now);
        } else {
            filters = filterRepository.findByMetricIdOrderByFilterOrderAsc(metricId);
        }

        cacheHelper.evictMetricAfterCommit(metricId);
        return toDefinition(saved, filters);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillableMetricDefinition> findMetricById(UUID metricId) {
        if (metricId == null) {
            return Optional.empty();
        }
        Optional<BillableMetricDefinition> cached = cacheHelper.getMetric(metricId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<BillableMetricDefinition> loaded = metricRepository.findById(metricId)
                .map(entity -> toDefinition(entity, filterRepository.findByMetricIdOrderByFilterOrderAsc(entity.getId())));
        loaded.ifPresent(cacheHelper::putMetric);
        return loaded;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BillableMetricDefinition> findMetricsByIds(Collection<UUID> metricIds) {
        if (metricIds == null || metricIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BillableMetricDefinition> result = new HashMap<>();
        Set<UUID> missed = new HashSet<>();
        for (UUID id : metricIds) {
            if (id == null) {
                continue;
            }
            Optional<BillableMetricDefinition> cached = cacheHelper.getMetric(id);
            if (cached.isPresent()) {
                result.put(id, cached.get());
            } else {
                missed.add(id);
            }
        }
        if (!missed.isEmpty()) {
            List<BillableMetricEntity> entities = metricRepository.findAllById(missed);
            List<UUID> loadedIds = entities.stream().map(BillableMetricEntity::getId).toList();
            Map<UUID, List<MetricFilterEntity>> filtersByMetric = new HashMap<>();
            if (!loadedIds.isEmpty()) {
                for (MetricFilterEntity filter : filterRepository
                        .findByMetricIdInOrderByMetricIdAscFilterOrderAsc(loadedIds)) {
                    filtersByMetric.computeIfAbsent(filter.getMetricId(), k -> new ArrayList<>()).add(filter);
                }
            }
            for (BillableMetricEntity entity : entities) {
                BillableMetricDefinition def = toDefinition(
                        entity,
                        filtersByMetric.getOrDefault(entity.getId(), List.of())
                );
                cacheHelper.putMetric(def);
                result.put(def.id(), def);
            }
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillableMetricDefinition> findMetricByKey(String metricKey, UUID customerId) {
        if (metricKey == null || metricKey.isBlank()) {
            return Optional.empty();
        }
        String normalizedKey = metricKey.trim().toLowerCase(Locale.ROOT);
        Optional<BillableMetricEntity> entity = customerId == null
                ? metricRepository.findByMetricKeyAndCustomerIdIsNull(normalizedKey)
                : metricRepository.findByMetricKeyAndCustomerId(normalizedKey, customerId);
        return entity.map(m -> toDefinition(m, filterRepository.findByMetricIdOrderByFilterOrderAsc(m.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillableMetricDefinition> listMetrics(Pageable pageable) {
        Page<BillableMetricEntity> page = metricRepository.findAll(pageable);
        return hydrateFilterPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillableMetricDefinition> listCustomerAccessibleMetrics(UUID customerId, Pageable pageable) {
        if (customerId == null) {
            throw new InvalidCatalogConfigurationException("customerId must not be null");
        }
        Page<BillableMetricEntity> page = metricRepository.findAllByCustomerIdIsNullOrCustomerId(customerId, pageable);
        return hydrateFilterPage(page);
    }

    private Page<BillableMetricDefinition> hydrateFilterPage(Page<BillableMetricEntity> page) {
        List<UUID> metricIds = page.getContent().stream().map(BillableMetricEntity::getId).toList();
        Map<UUID, List<MetricFilterEntity>> filtersByMetric = new HashMap<>();
        if (!metricIds.isEmpty()) {
            for (MetricFilterEntity filter : filterRepository
                    .findByMetricIdInOrderByMetricIdAscFilterOrderAsc(metricIds)) {
                filtersByMetric.computeIfAbsent(filter.getMetricId(), k -> new ArrayList<>()).add(filter);
            }
        }
        return page.map(entity -> toDefinition(entity, filtersByMetric.getOrDefault(entity.getId(), List.of())));
    }

    private List<MetricFilterEntity> persistFilters(
            BillableMetricEntity metric,
            List<MetricFilterDefinition> filters,
            Instant now
    ) {
        if (filters.isEmpty()) {
            return List.of();
        }
        List<MetricFilterEntity> entities = new ArrayList<>(filters.size());
        for (MetricFilterDefinition f : filters) {
            entities.add(new MetricFilterEntity(
                    metric,
                    f.filterOrder(),
                    f.propertyKey(),
                    f.operator(),
                    f.values(),
                    now
            ));
        }
        return filterRepository.saveAllAndFlush(entities);
    }

    private static List<MetricFilterDefinition> validateFilters(List<MetricFilterDefinition> rawFilters) {
        if (rawFilters == null || rawFilters.isEmpty()) {
            return List.of();
        }
        List<MetricFilterDefinition> sorted = rawFilters.stream()
                .sorted(Comparator.comparingInt(MetricFilterDefinition::filterOrder))
                .toList();

        List<MetricFilterDefinition> validated = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            MetricFilterDefinition f = sorted.get(i);
            int expectedOrder = i + 1;
            if (f.filterOrder() != expectedOrder) {
                throw new InvalidCatalogConfigurationException(
                        "Metric filter orders must be sequential starting at 1; expected " + expectedOrder
                );
            }
            String propKey = requireNonBlank(f.propertyKey(), "Filter propertyKey must not be blank", 120);
            if (f.operator() == null) {
                throw new InvalidCatalogConfigurationException("Filter operator must not be null");
            }
            List<String> values = f.values().stream()
                    .map(v -> v == null ? "" : v.trim())
                    .filter(v -> !v.isEmpty())
                    .toList();

            if (f.operator() == MetricFilterOperator.EXISTS) {
                validated.add(new MetricFilterDefinition(expectedOrder, propKey, f.operator(), List.of()));
            } else {
                if (values.isEmpty()) {
                    throw new InvalidCatalogConfigurationException(
                            "Filter operator " + f.operator() + " requires at least one non-blank value"
                    );
                }
                if ((f.operator() == MetricFilterOperator.EQUALS || f.operator() == MetricFilterOperator.NOT_EQUALS)
                        && values.size() != 1) {
                    throw new InvalidCatalogConfigurationException(
                            "Filter operator " + f.operator() + " requires exactly one comparison value"
                    );
                }
                validated.add(new MetricFilterDefinition(expectedOrder, propKey, f.operator(), values));
            }
        }
        return validated;
    }

    private static String validateTargetProperty(MetricAggregationType type, String rawTargetProperty) {
        String trimmed = trimNullable(rawTargetProperty);
        if (type.requiresTargetProperty() && trimmed == null) {
            throw new InvalidCatalogConfigurationException(
                    "Aggregation type " + type + " requires a non-blank targetProperty"
            );
        }
        if (trimmed != null && trimmed.length() > 120) {
            throw new InvalidCatalogConfigurationException("targetProperty exceeds maximum length of 120");
        }
        return trimmed;
    }

    private static String normalizeKey(String key) {
        String trimmed = requireNonBlank(key, "Metric key must not be blank", 80);
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidCatalogConfigurationException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidCatalogConfigurationException("Value exceeds maximum length of " + maxLength);
        }
        return trimmed;
    }

    private static String trimNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Instant nowUtc() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BillableMetricDefinition toDefinition(
            BillableMetricEntity entity,
            List<MetricFilterEntity> filterEntities
    ) {
        List<MetricFilterDefinition> filters = filterEntities.stream()
                .map(f -> new MetricFilterDefinition(
                        f.getFilterOrder(),
                        f.getPropertyKey(),
                        f.getOperator(),
                        f.getFilterValues()
                ))
                .toList();
        return new BillableMetricDefinition(
                entity.getId(),
                entity.getMetricKey(),
                entity.getName(),
                entity.getDescription(),
                entity.getAggregationType(),
                entity.getTargetProperty(),
                entity.getCustomerId(),
                entity.isActive(),
                filters,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
