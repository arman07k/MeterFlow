package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.catalog.api.AllowanceResetPeriod;
import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.ChargeTierDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.ConfigureAllowanceCommand;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.ConfigureCommitmentCommand;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.ImmutablePlanVersionException;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.catalog.api.MetricFilterOperator;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.PricingSimulationFacade;
import com.meterflow.catalog.api.PricingSimulationRequest;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.catalog.api.UpdateMetricCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.shared.CacheNames;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(CatalogModuleIT.PostgresContainerConfiguration.class)
class CatalogModuleIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private MetricCatalogFacade metricCatalog;

    @Autowired
    private PlanCatalogFacade planCatalog;

    @Autowired
    private RatingEngineFacade ratingEngine;

    @Autowired
    private PricingSimulationFacade simulationFacade;

    @Autowired
    private IdentityManagementFacade identityManagement;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        // Bypass triggers only during test setup cleanup on isolated Testcontainers DB
        jdbcTemplate.execute("TRUNCATE TABLE minimum_commitments, free_allowances, charge_tiers, plan_charges, plan_versions, pricing_plans, metric_filters, billable_metrics, customer_memberships, users, customers RESTART IDENTITY CASCADE");
        cacheManager.getCache(CacheNames.PLAN_VERSIONS).clear();
        cacheManager.getCache(CacheNames.METRIC_DEFINITIONS).clear();
    }

    @Test
    void enforcesPublishedImmutabilityAtServiceAndPostgreSqlTriggerLevelsAndSupportsIndependentCloning() {
        BillableMetricDefinition apiMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "api_calls",
                "API Calls",
                "Total billable API calls",
                MetricAggregationType.COUNT,
                null,
                null,
                List.of(new MetricFilterDefinition(1, "env", MetricFilterOperator.EQUALS, List.of("prod")))
        ));

        PlanVersionDetail v1Draft = planCatalog.createPlanWithDraft(new CreatePlanCommand(
                "pro-bdt",
                "Pro Plan BDT",
                "Production BDT plan",
                SupportedCurrency.BDT,
                Instant.parse("2026-11-01T00:00:00Z")
        ));

        // Cannot publish empty draft
        assertThatThrownBy(() -> planCatalog.publishVersion(v1Draft.id()))
                .isInstanceOf(InvalidCatalogConfigurationException.class);

        // Reject commitment with mismatched currency
        assertThatThrownBy(() -> planCatalog.updateDraftVersion(v1Draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-11-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "base", "Base", ChargeTypes.FLAT_FEE, null,
                        new BigDecimal("1000.00"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of(new ConfigureCommitmentCommand(null, Money.usd("50.00")))
        ))).isInstanceOf(CurrencyMismatchException.class);

        // Configure valid v1 draft with FLAT_FEE, GRADUATED_TIERS, PER_SEAT, allowance, and commitment
        PlanVersionDetail v1Configured = planCatalog.updateDraftVersion(v1Draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-11-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "base", "Monthly Base", ChargeTypes.FLAT_FEE, null,
                                new BigDecimal("500.00"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "api_tiers", "API Graduated", ChargeTypes.GRADUATED_TIERS, apiMetric.id(),
                                null, null, null, null,
                                List.of(
                                        new ChargeTierDefinition(1, BigDecimal.ZERO, new BigDecimal("1000"), new BigDecimal("0.50"), BigDecimal.ZERO, "First 1k"),
                                        new ChargeTierDefinition(2, new BigDecimal("1000"), null, new BigDecimal("0.25"), BigDecimal.ZERO, "1k+")
                                ), Map.of()),
                        new ConfigureChargeCommand(3, "seats", "Seat Fee", ChargeTypes.PER_SEAT, null,
                                null, new BigDecimal("200.00"), null, null, List.of(), Map.of())
                ),
                List.of(new ConfigureAllowanceCommand(apiMetric.id(), null, new BigDecimal("200"), AllowanceResetPeriod.BILLING_PERIOD)),
                List.of(new ConfigureCommitmentCommand(null, Money.bdt("1500.00")))
        ));
        assertThat(v1Configured.charges()).hasSize(3);

        PlanVersionDetail v1Published = planCatalog.publishVersion(v1Draft.id());
        assertThat(v1Published.status()).isEqualTo(PlanVersionStatus.PUBLISHED);
        assertThat(v1Published.publishedAt()).isNotNull();

        // Populate Caffeine cache on read of PUBLISHED version
        assertThat(planCatalog.findPlanVersionById(v1Published.id())).isPresent();
        assertThat(cacheManager.getCache(CacheNames.PLAN_VERSIONS).get(v1Published.id())).isNotNull();

        // Service-level immutability rejection
        assertThatThrownBy(() -> planCatalog.updateDraftVersion(v1Published.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-12-01T00:00:00Z"), List.of(), List.of(), List.of())))
                .isInstanceOf(ImmutablePlanVersionException.class);
        assertThatThrownBy(() -> planCatalog.publishVersion(v1Published.id()))
                .isInstanceOf(ImmutablePlanVersionException.class);

        // Database-level PostgreSQL trigger immutability enforcement
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE plan_versions SET effective_from = NOW() WHERE id = ?", v1Published.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable PUBLISHED");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM plan_versions WHERE id = ?", v1Published.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable PUBLISHED");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE plan_charges SET flat_amount = 9999 WHERE plan_version_id = ?", v1Published.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable PUBLISHED");

        // Clone v1 -> v2 DRAFT, modify and publish v2 -> v1 remains completely unchanged
        PlanVersionDetail v2Draft = planCatalog.cloneVersionToDraft(v1Published.id(), Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(v2Draft.versionNumber()).isEqualTo(2);
        assertThat(v2Draft.status()).isEqualTo(PlanVersionStatus.DRAFT);
        assertThat(v2Draft.clonedFromVersionId()).isEqualTo(v1Published.id());
        assertThat(v2Draft.charges()).hasSize(3);
        assertThat(v2Draft.freeAllowances()).hasSize(1);
        assertThat(v2Draft.minimumCommitments()).hasSize(1);

        planCatalog.updateDraftVersion(v2Draft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-12-01T00:00:00Z"),
                List.of(new ConfigureChargeCommand(1, "base", "Updated Base", ChargeTypes.FLAT_FEE, null,
                        new BigDecimal("900.00"), null, null, null, List.of(), Map.of())),
                List.of(),
                List.of()
        ));
        PlanVersionDetail v2Published = planCatalog.publishVersion(v2Draft.id());

        PlanVersionDetail historicalV1 = planCatalog.findPlanVersionById(v1Published.id()).orElseThrow();
        assertThat(historicalV1.charges()).hasSize(3);
        assertThat(historicalV1.charges().getFirst().flatAmount()).isEqualByComparingTo("500.0000");
        assertThat(v2Published.charges()).hasSize(1);
        assertThat(v2Published.charges().getFirst().flatAmount()).isEqualByComparingTo("900.0000");
        assertThat(planCatalog.listPlanVersions(v1Published.planId())).hasSize(2);
    }

    @Test
    void handlesConcurrentDraftVersionCreationSafelyAndEvictsMetricCacheAfterCommitAndSupportsRollback() throws Exception {
        PlanVersionDetail initial = planCatalog.createPlanWithDraft(new CreatePlanCommand(
                "concurrent-plan", "Concurrent Plan", null, SupportedCurrency.USD, null));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<PlanVersionDetail>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> planCatalog.createDraftVersion(initial.planId(), null));
            }
            List<Future<PlanVersionDetail>> futures = pool.invokeAll(tasks);
            Set<Integer> versionNumbers = futures.stream()
                    .map(f -> {
                        try {
                            return f.get().versionNumber();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertThat(versionNumbers).containsExactlyInAnyOrder(2, 3, 4, 5);
        } finally {
            pool.shutdownNow();
        }

        // Metric cache read-through and after-commit eviction
        BillableMetricDefinition metric = metricCatalog.createMetric(new CreateMetricCommand(
                "compute_sec", "Compute Seconds", null, MetricAggregationType.SUM, "durationSec", null, List.of()));
        assertThat(metricCatalog.findMetricById(metric.id())).isPresent();
        assertThat(cacheManager.getCache(CacheNames.METRIC_DEFINITIONS).get(metric.id())).isNotNull();

        metricCatalog.updateMetric(metric.id(), new UpdateMetricCommand("Compute Seconds v2", null, null, true, null));
        assertThat(cacheManager.getCache(CacheNames.METRIC_DEFINITIONS).get(metric.id())).isNull();
        assertThat(metricCatalog.findMetricById(metric.id()).orElseThrow().name()).isEqualTo("Compute Seconds v2");

        // Transaction rollback verification
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> txTemplate.executeWithoutResult(status -> {
            planCatalog.createPlanWithDraft(new CreatePlanCommand(
                    "rollback-plan", "Rollback", null, SupportedCurrency.BDT, null));
            throw new IllegalStateException("Forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pricing_plans WHERE plan_code = 'rollback-plan'", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void enforcesRestAuthorizationCustomerIsolationAndReadOnlyPricingSimulationParity() throws Exception {
        CustomerSummary tenantA = identityManagement.createCustomer("cat-a", "Tenant A", SupportedCurrency.BDT);
        CustomerSummary tenantB = identityManagement.createCustomer("cat-b", "Tenant B", SupportedCurrency.BDT);

        UserSummary operator = identityManagement.createUser(
                "cat-ops@meterflow.example", "Catalog Ops", "CatalogOpsPass#2026", PlatformRole.OPERATOR);
        UserSummary custA = identityManagement.createUser(
                "cat-a@meterflow.example", "Customer A", "CustomerAPass#2026", PlatformRole.CUSTOMER);
        identityManagement.assignMembership(custA.id(), tenantA.id(), MembershipRole.OWNER);

        BillableMetricDefinition globalMetric = metricCatalog.createMetric(new CreateMetricCommand(
                "global_req", "Global Requests", null, MetricAggregationType.COUNT, null, null, List.of()));
        metricCatalog.createMetric(new CreateMetricCommand(
                "tenant_a_custom", "Tenant A Metric", null, MetricAggregationType.SUM, "qty", tenantA.id(), List.of()));
        metricCatalog.createMetric(new CreateMetricCommand(
                "tenant_b_secret", "Tenant B Secret Metric", null, MetricAggregationType.SUM, "qty", tenantB.id(), List.of()));

        // Customer A lists only global + own metrics (never Tenant B's private metric)
        mockMvc.perform(get("/api/v1/customer/" + tenantA.id() + "/catalog/metrics")
                        .with(httpBasic(custA.email(), "CustomerAPass#2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        // Customer A denied on Tenant B's metrics -> 403
        mockMvc.perform(get("/api/v1/customer/" + tenantB.id() + "/catalog/metrics")
                        .with(httpBasic(custA.email(), "CustomerAPass#2026")))
                .andExpect(status().isForbidden());

        // Customer A cannot create catalog plans -> 403
        mockMvc.perform(post("/api/v1/catalog/plans")
                        .with(httpBasic(custA.email(), "CustomerAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"unauth\",\"name\":\"Unauth\",\"currency\":\"BDT\"}"))
                .andExpect(status().isForbidden());

        // Operator creates plan, configures PACKAGE + PER_SEAT, keeps v1 DRAFT first
        PlanVersionDetail draft = planCatalog.createPlanWithDraft(new CreatePlanCommand(
                "sim-plan", "Simulation Plan", null, SupportedCurrency.BDT, null));
        planCatalog.updateDraftVersion(draft.id(), new UpdateDraftPlanVersionCommand(
                null,
                List.of(
                        new ConfigureChargeCommand(1, "req_pkg", "Per 1k Requests", ChargeTypes.PACKAGE, globalMetric.id(),
                                null, null, new BigDecimal("1000"), new BigDecimal("100.00"), List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "seats", "Per Seat", ChargeTypes.PER_SEAT, null,
                                null, new BigDecimal("250.00"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of(new ConfigureCommitmentCommand(null, Money.bdt("500.00")))
        ));

        String simBody = """
                {
                  "targetPlanVersionId": "%s",
                  "currency": "BDT",
                  "seatQuantity": 2,
                  "historicalUsageSnapshot": [
                    { "metricId": "%s", "aggregatedQuantity": 1500 }
                  ]
                }
                """.formatted(draft.id(), globalMetric.id());

        // Customer A denied simulating against DRAFT plan version -> 403
        mockMvc.perform(post("/api/v1/customer/" + tenantA.id() + "/catalog/simulations")
                        .with(httpBasic(custA.email(), "CustomerAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simBody))
                .andExpect(status().isForbidden());

        // Operator CAN publish via REST and simulate cleanly
        mockMvc.perform(post("/api/v1/catalog/plan-versions/" + draft.id() + "/publish")
                        .with(httpBasic(operator.email(), "CatalogOpsPass#2026")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        // Editing PUBLISHED via REST fails with 409 Conflict ProblemDetail
        mockMvc.perform(put("/api/v1/catalog/plan-versions/" + draft.id())
                        .with(httpBasic(operator.email(), "CatalogOpsPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"charges\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:meterflow:error:immutable-plan-version"));

        // Snapshot DB counts before simulation to prove strictly read-only execution
        int versionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM plan_versions", Integer.class);
        int chargesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM plan_charges", Integer.class);
        int eventsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_publication", Integer.class);

        // Customer A simulates PUBLISHED plan for Tenant A:
        // CEIL(1500/1000)=2 * 100 = 200.00 + (2 * 250 = 500.00) = 700.00 BDT (>= 500 min -> true-up 0)
        mockMvc.perform(post("/api/v1/customer/" + tenantA.id() + "/catalog/simulations")
                        .with(httpBasic(custA.email(), "CustomerAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finalPayableAmount.amount").value("700.00"))
                .andExpect(jsonPath("$.finalPayableAmount.currency").value("BDT"))
                .andExpect(jsonPath("$.planMinimumTrueUp.amount").value("0.0000"));

        // Cross-tenant simulation attempt rejected -> 403
        mockMvc.perform(post("/api/v1/customer/" + tenantB.id() + "/catalog/simulations")
                        .with(httpBasic(custA.email(), "CustomerAPass#2026"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simBody))
                .andExpect(status().isForbidden());

        // Exact parity between PricingSimulationFacade and RatingEngineFacade + zero DB side effects
        PlanRatingResult directRate = ratingEngine.rate(new RatingRequest(
                tenantA.id(), draft.id(), SupportedCurrency.BDT, null, null, 2,
                List.of(new MetricUsageInput(globalMetric.id(), new BigDecimal("1500"))), null));
        PlanRatingResult simulated = simulationFacade.simulate(new PricingSimulationRequest(
                tenantA.id(), draft.id(), SupportedCurrency.BDT, null, null, 2,
                List.of(new MetricUsageInput(globalMetric.id(), new BigDecimal("1500"))), null));

        assertThat(simulated).isEqualTo(directRate);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM plan_versions", Integer.class)).isEqualTo(versionsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM plan_charges", Integer.class)).isEqualTo(chargesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_publication", Integer.class)).isEqualTo(eventsBefore);
    }
}
