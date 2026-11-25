package com.meterflow.portal.internal;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.catalog.api.UpdateMetricCommand;
import com.meterflow.identity.api.CustomerAccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogMetricController {

    private static final int MAX_PAGE_SIZE = 100;

    private final MetricCatalogFacade metricCatalogFacade;
    private final CustomerAccessGuard customerAccessGuard;

    CatalogMetricController(
            MetricCatalogFacade metricCatalogFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.metricCatalogFacade = metricCatalogFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    @PostMapping("/api/v1/catalog/metrics")
    ResponseEntity<BillableMetricDefinition> createMetric(@Valid @RequestBody CreateMetricRequest request) {
        BillableMetricDefinition created = metricCatalogFacade.createMetric(new CreateMetricCommand(
                request.metricKey(),
                request.name(),
                request.description(),
                request.aggregationType(),
                request.targetProperty(),
                request.customerId(),
                request.filters()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/v1/catalog/metrics")
    PageResponse<BillableMetricDefinition> listMetrics(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "metricKey")
        );
        return PageResponse.from(metricCatalogFacade.listMetrics(pageable));
    }

    @GetMapping("/api/v1/catalog/metrics/{metricId}")
    BillableMetricDefinition getMetric(@PathVariable UUID metricId) {
        return metricCatalogFacade.findMetricById(metricId)
                .orElseThrow(() -> new CatalogResourceNotFoundException("Billable metric not found"));
    }

    @PutMapping("/api/v1/catalog/metrics/{metricId}")
    BillableMetricDefinition updateMetric(
            @PathVariable UUID metricId,
            @Valid @RequestBody UpdateMetricRequest request
    ) {
        return metricCatalogFacade.updateMetric(metricId, new UpdateMetricCommand(
                request.name(),
                request.description(),
                request.targetProperty(),
                request.active(),
                request.filters()
        ));
    }

    @GetMapping("/api/v1/customer/{customerId}/catalog/metrics")
    PageResponse<BillableMetricDefinition> listCustomerMetrics(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(
                Math.max(0, page),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "metricKey")
        );
        return PageResponse.from(metricCatalogFacade.listCustomerAccessibleMetrics(customerId, pageable));
    }

    record CreateMetricRequest(
            @NotBlank String metricKey,
            @NotBlank String name,
            String description,
            @NotNull MetricAggregationType aggregationType,
            String targetProperty,
            UUID customerId,
            List<MetricFilterDefinition> filters
    ) {
    }

    record UpdateMetricRequest(
            String name,
            String description,
            String targetProperty,
            Boolean active,
            List<MetricFilterDefinition> filters
    ) {
    }
}
