package com.meterflow.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterflow.billing.api.ConfigureTaxPolicyCommand;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.TaxPolicyFacade;
import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.ConfigureChargeCommand;
import com.meterflow.catalog.api.CreateMetricCommand;
import com.meterflow.catalog.api.CreatePlanCommand;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.UpdateDraftPlanVersionCommand;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityManagementFacade;
import com.meterflow.identity.api.MembershipRole;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UserSummary;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.ThresholdEvaluationCommand;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.metering.api.UsageThresholdFacade;
import com.meterflow.payments.api.InitiatePaymentCommand;
import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentProcessingFacade;
import com.meterflow.shared.BillingInterval;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.CreateSubscriptionCommand;
import com.meterflow.subscriptions.api.SubscriptionManagementFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.TrialConversionPolicy;
import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookEventTypes;
import com.meterflow.webhooks.api.WebhookSignatureVerifier;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WebhookNotificationAndSimulationIT.PostgresContainerConfiguration.class)
class WebhookNotificationAndSimulationIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IdentityManagementFacade identityManagementFacade;
    @Autowired private MetricCatalogFacade metricCatalogFacade;
    @Autowired private PlanCatalogFacade planCatalogFacade;
    @Autowired private SubscriptionManagementFacade subscriptionManagementFacade;
    @Autowired private UsageIngestionFacade usageIngestionFacade;
    @Autowired private UsageThresholdFacade usageThresholdFacade;
    @Autowired private TaxPolicyFacade taxPolicyFacade;
    @Autowired private InvoiceManagementFacade invoiceManagementFacade;
    @Autowired private PaymentProcessingFacade paymentProcessingFacade;
    @Autowired private WebhookDeliveryFacade webhookDeliveryFacade;

    private HttpServer receiverServer;
    private int receiverPort;
    private final List<CapturedWebhook> capturedRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger failFirstAttempts = new AtomicInteger(0);

    record CapturedWebhook(String signatureHeader, String eventTypeHeader, String deliveryIdHeader, String body) {
    }

    @BeforeEach
    void startReceiver() throws IOException {
        capturedRequests.clear();
        failFirstAttempts.set(0);
        receiverServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiverServer.createContext("/hooks", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String sig = exchange.getRequestHeaders().getFirst(WebhookSignatureVerifier.SIGNATURE_HEADER);
            String eventType = exchange.getRequestHeaders().getFirst(WebhookSignatureVerifier.EVENT_TYPE_HEADER);
            String deliveryId = exchange.getRequestHeaders().getFirst(WebhookSignatureVerifier.DELIVERY_ID_HEADER);
            capturedRequests.add(new CapturedWebhook(sig, eventType, deliveryId, body));

            int status = failFirstAttempts.getAndDecrement() > 0 ? 503 : 200;
            byte[] resp = (status == 200 ? "{\"received\":true}" : "temporary unavailable")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        receiverServer.start();
        receiverPort = receiverServer.getAddress().getPort();
    }

    @AfterEach
    void stopReceiver() {
        if (receiverServer != null) {
            receiverServer.stop(0);
        }
    }

    @Test
    void webhooksDeliverWithDualKeyHmacBackoffReplayAuditNotificationsAndHistoricalSimulation() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = "admin-" + suffix + "@meterflow.test";
        String custAEmail = "custa-" + suffix + "@meterflow.test";
        String custBEmail = "custb-" + suffix + "@meterflow.test";
        String pass = "MeterFlowTest#2026";

        identityManagementFacade.createUser(adminEmail, "Admin User", pass, PlatformRole.ADMIN);
        UserSummary userA = identityManagementFacade.createUser(custAEmail, "Customer A User", pass, PlatformRole.CUSTOMER);
        UserSummary userB = identityManagementFacade.createUser(custBEmail, "Customer B User", pass, PlatformRole.CUSTOMER);

        CustomerSummary customerA = identityManagementFacade.createCustomer("cust-a-" + suffix, "Acme Alpha", SupportedCurrency.BDT);
        CustomerSummary customerB = identityManagementFacade.createCustomer("cust-b-" + suffix, "Beta Corp", SupportedCurrency.BDT);
        identityManagementFacade.assignMembership(userA.id(), customerA.id(), MembershipRole.OWNER);
        identityManagementFacade.assignMembership(userB.id(), customerB.id(), MembershipRole.OWNER);

        taxPolicyFacade.configureTaxPolicy(new ConfigureTaxPolicyCommand(
                "BD-VAT-" + suffix,
                "BD VAT 15%",
                customerA.id(),
                "BD",
                "VAT",
                new BigDecimal("15.00"),
                false
        ));

        BillableMetricDefinition metric = metricCatalogFacade.createMetric(new CreateMetricCommand(
                "api_calls_" + suffix,
                "API Calls",
                "Metered API requests",
                MetricAggregationType.SUM,
                "quantity",
                null,
                List.of()
        ));

        PlanVersionDetail starterDraft = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "STARTER-" + suffix,
                "Starter Plan",
                "Base starter plan",
                SupportedCurrency.BDT,
                Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(starterDraft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "FLAT_FEE", "Platform Fee", "FLAT_FEE", null,
                                new BigDecimal("1000.00"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "API_USAGE", "API Per Call", "PER_UNIT", metric.id(),
                                null, new BigDecimal("2.00"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        PlanVersionDetail starterPublished = planCatalogFacade.publishVersion(starterDraft.id());

        PlanVersionDetail growthDraft = planCatalogFacade.createPlanWithDraft(new CreatePlanCommand(
                "GROWTH-" + suffix,
                "Growth Plan",
                "Higher flat fee, lower unit rate",
                SupportedCurrency.BDT,
                Instant.parse("2026-01-01T00:00:00Z")
        ));
        planCatalogFacade.updateDraftVersion(growthDraft.id(), new UpdateDraftPlanVersionCommand(
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(
                        new ConfigureChargeCommand(1, "FLAT_FEE", "Growth Platform Fee", "FLAT_FEE", null,
                                new BigDecimal("1500.00"), null, null, null, List.of(), Map.of()),
                        new ConfigureChargeCommand(2, "API_USAGE", "Growth API Per Call", "PER_UNIT", metric.id(),
                                null, new BigDecimal("0.50"), null, null, List.of(), Map.of())
                ),
                List.of(),
                List.of()
        ));
        PlanVersionDetail growthPublished = planCatalogFacade.publishVersion(growthDraft.id());
        PlanVersionDetail growthUnpublishedDraft = planCatalogFacade.cloneVersionToDraft(
                growthPublished.id(),
                Instant.parse("2026-02-01T00:00:00Z")
        );

        // 1. Register webhook endpoint as Customer A and verify one-time secret + rotation + tenant isolation
        String hookUrl = "http://127.0.0.1:" + receiverPort + "/hooks";
        String registerBody = """
                {
                  "name": "Primary Billing Receiver",
                  "targetUrl": "%s",
                  "subscribedEvents": ["invoice.finalised", "payment.failed", "usage.threshold_reached"]
                }
                """.formatted(hookUrl);

        MvcResult regResult = mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/webhooks/endpoints")
                        .with(httpBasic(custAEmail, pass))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signingSecret").isString())
                .andExpect(jsonPath("$.endpoint.active").value(true))
                .andReturn();

        JsonNode regJson = jsonMapper.readTree(regResult.getResponse().getContentAsString());
        UUID endpointId = UUID.fromString(regJson.get("endpoint").get("id").asString());
        String initialSecret = regJson.get("signingSecret").asString();
        assertThat(initialSecret).startsWith("whsec_");

        // Secret must NEVER be redisplayed in subsequent list responses
        String listJson = mockMvc.perform(get("/api/v1/customer/" + customerA.id() + "/webhooks/endpoints")
                        .with(httpBasic(custAEmail, pass)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listJson).doesNotContain(initialSecret);

        // Cross-tenant isolation: Customer B cannot read Customer A's endpoints
        mockMvc.perform(get("/api/v1/customer/" + customerA.id() + "/webhooks/endpoints")
                        .with(httpBasic(custBEmail, pass)))
                .andExpect(status().isForbidden());

        // Rotate secret -> activates 24h dual-key grace window
        MvcResult rotResult = mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/webhooks/endpoints/" + endpointId + "/rotate-secret")
                        .with(httpBasic(custAEmail, pass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endpoint.dualKeyGraceActive").value(true))
                .andReturn();
        JsonNode rotJson = jsonMapper.readTree(rotResult.getResponse().getContentAsString());
        String rotatedSecret = rotJson.get("signingSecret").asString();
        assertThat(rotatedSecret).startsWith("whsec_").isNotEqualTo(initialSecret);

        // 2. Create subscription spanning current time (<72h lookback per FR1), ingest 1,000 API calls, trigger usage threshold, finalize invoice, fail payment
        Instant anchorStart = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS).minus(24, java.time.temporal.ChronoUnit.HOURS);
        SubscriptionSummary subscription = subscriptionManagementFacade.createSubscription(new CreateSubscriptionCommand(
                customerA.id(),
                starterPublished.id(),
                SupportedCurrency.BDT,
                BillingInterval.MONTHLY,
                "UTC",
                1,
                anchorStart,
                null,
                TrialConversionPolicy.EXPIRE_ON_END,
                "sub-wh-" + suffix
        ));
        Instant periodStart = subscription.currentPeriodStart();
        Instant periodEnd = subscription.currentPeriodEnd();

        usageIngestionFacade.ingestBatch(new IngestUsageBatchCommand(List.of(
                new IngestUsageEventCommand(
                        UUID.randomUUID(),
                        customerA.id(),
                        metric.id(),
                        subscription.id(),
                        new BigDecimal("1000"),
                        periodStart.plusSeconds(3600),
                        "ev-wh-1-" + suffix,
                        Map.of("quantity", 1000)
                )
        )));

        usageThresholdFacade.evaluateThresholds(new ThresholdEvaluationCommand(
                customerA.id(),
                subscription.id(),
                metric.id(),
                "monthly-api-cap",
                periodStart,
                periodEnd,
                new BigDecimal("1000"),
                new BigDecimal("1000")
        ));

        InvoiceDetail draft = invoiceManagementFacade.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                subscription.id(),
                periodStart,
                periodEnd,
                0,
                null,
                "draft-wh-" + suffix
        ));
        // Original: 1000 flat + 1000 * 2.00 = 3000.00 subtotal + 15% VAT (450.00) = 3450.00 BDT
        InvoiceDetail finalized = invoiceManagementFacade.finalizeInvoice(
                draft.invoice().id(),
                new FinalizeInvoiceCommand(false, Instant.parse("2026-11-15T00:00:00Z"), "fin-wh-" + suffix)
        );
        assertThat(finalized.invoice().totalAmount().amountAsPlainString()).isEqualTo("3450.0000");

        paymentProcessingFacade.initiatePayment(new InitiatePaymentCommand(
                finalized.invoice().id(),
                null,
                SupportedCurrency.BDT,
                MockPaymentOutcome.SIMULATE_SOFT_DECLINE,
                "pay-fail-" + suffix
        ));

        // Wait briefly for Modulith @ApplicationModuleListener async delivery/notification persistence
        awaitDeliveriesAndNotifications(customerA.id(), 3, 3);

        // 3. Test 503 -> 503 -> 200 exponential backoff + dual-key HMAC verification on real HTTP deliveries
        failFirstAttempts.set(2);
        var firstCycle = webhookDeliveryFacade.dispatchDueDeliveries();
        assertThat(firstCycle.claimedCount()).isGreaterThanOrEqualTo(3);
        assertThat(firstCycle.scheduledRetryCount()).isEqualTo(2);
        assertThat(firstCycle.deliveredCount()).isGreaterThanOrEqualTo(1);

        // Make retrying deliveries due now to verify retry progression deterministically
        jdbcTemplate.update(
                "UPDATE webhook_deliveries SET next_attempt_at = NOW() - INTERVAL '1 second' WHERE customer_id = ? AND status = 'RETRYING'",
                customerA.id()
        );
        var secondCycle = webhookDeliveryFacade.dispatchDueDeliveries();
        assertThat(secondCycle.claimedCount()).isEqualTo(2);
        assertThat(secondCycle.deliveredCount()).isEqualTo(2);

        // Every captured HTTP request must verify under BOTH the rotated active secret AND the previous secret (dual-key grace!)
        assertThat(capturedRequests).hasSizeGreaterThanOrEqualTo(5);
        assertThat(capturedRequests).extracting(CapturedWebhook::eventTypeHeader)
                .contains(
                        WebhookEventTypes.INVOICE_FINALISED,
                        WebhookEventTypes.PAYMENT_FAILED,
                        WebhookEventTypes.USAGE_THRESHOLD_REACHED
                );
        for (CapturedWebhook req : capturedRequests) {
            assertThat(WebhookSignatureVerifier.verifySignature(
                    req.signatureHeader(), req.body(), List.of(rotatedSecret), Instant.now()
            )).isTrue();
            assertThat(WebhookSignatureVerifier.verifySignature(
                    req.signatureHeader(), req.body(), List.of(initialSecret), Instant.now()
            )).isTrue();
        }

        // 4. Auditable manual replay via Customer REST API -> appends attempt & audit log entry
        UUID sampleDeliveryId = UUID.fromString(capturedRequests.getFirst().deliveryIdHeader());
        mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/webhooks/deliveries/" + sampleDeliveryId + "/replay")
                        .with(httpBasic(custAEmail, pass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.status").value("DELIVERED"))
                .andExpect(jsonPath("$.attempts[?(@.manualReplay == true)]").isNotEmpty());

        awaitAuditLog(customerA.id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE customer_id = ? AND action = 'WEBHOOK_DELIVERY_REPLAY'",
                Integer.class,
                customerA.id()
        )).isGreaterThanOrEqualTo(1);

        // 5. Verify persistent notifications & mark-as-read + cross-tenant isolation
        MvcResult notifList = mockMvc.perform(get("/api/v1/customer/" + customerA.id() + "/notifications")
                        .with(httpBasic(custAEmail, pass)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode notifRoot = jsonMapper.readTree(notifList.getResponse().getContentAsString());
        assertThat(notifRoot.get("items").size()).isGreaterThanOrEqualTo(3);
        String firstNotifId = notifRoot.get("items").get(0).get("id").asString();

        mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/notifications/" + firstNotifId + "/read")
                        .with(httpBasic(custAEmail, pass)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(get("/api/v1/customer/" + customerA.id() + "/notifications")
                        .with(httpBasic(custBEmail, pass)))
                .andExpect(status().isForbidden());

        // 6. FR10 Historical Invoice Simulation: compare finalized invoice (3,450 BDT) vs Growth Plan
        // Simulated Growth: 1500 flat + 1000 * 0.50 = 2000.00 subtotal + 15% VAT (300.00) = 2300.00 BDT
        // Expected Delta: 2300.00 - 3450.00 = -1150.00 BDT (-33.33%)
        Integer invoicesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class);
        Integer creditGrantsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prepaid_credit_grants", Integer.class);
        Integer paymentsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class);

        String simPayload = """
                {
                  "originalInvoiceId": "%s",
                  "targetPlanVersionId": "%s"
                }
                """.formatted(finalized.invoice().id(), growthPublished.id());

        mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/billing/simulations/historical")
                        .with(httpBasic(custAEmail, pass))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulation").value(true))
                .andExpect(jsonPath("$.financialRecordMutated").value(false))
                .andExpect(jsonPath("$.originalTotal.amount").value("3450.0000"))
                .andExpect(jsonPath("$.simulatedSubtotal.amount").value("2000.0000"))
                .andExpect(jsonPath("$.simulatedTaxTotal.amount").value("300.0000"))
                .andExpect(jsonPath("$.simulatedTotal.amount").value("2300.0000"))
                .andExpect(jsonPath("$.totalDelta.amount").value("-1150.0000"))
                .andExpect(jsonPath("$.totalDeltaPercent").value(-33.33));

        // Zero DB mutations verified
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isEqualTo(invoicesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prepaid_credit_grants", Integer.class)).isEqualTo(creditGrantsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class)).isEqualTo(paymentsBefore);

        // Customer cannot simulate DRAFT plan versions (403); Admin CAN simulate DRAFT plan versions (200)
        String draftSimPayload = """
                {
                  "originalInvoiceId": "%s",
                  "targetPlanVersionId": "%s"
                }
                """.formatted(finalized.invoice().id(), growthUnpublishedDraft.id());

        mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/billing/simulations/historical")
                        .with(httpBasic(custAEmail, pass))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftSimPayload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/billing/simulations/historical")
                        .with(httpBasic(adminEmail, pass))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftSimPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulatedPlanStatus").value("DRAFT"));

        // Customer B cannot simulate Customer A's invoice
        mockMvc.perform(post("/api/v1/customer/" + customerA.id() + "/billing/simulations/historical")
                        .with(httpBasic(custBEmail, pass))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(simPayload))
                .andExpect(status().isForbidden());
    }

    private void awaitDeliveriesAndNotifications(UUID customerId, int expectedDeliveries, int expectedNotifications)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000L;
        while (System.currentTimeMillis() < deadline) {
            Integer deliveries = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM webhook_deliveries WHERE customer_id = ?",
                    Integer.class,
                    customerId
            );
            Integer notifications = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE customer_id = ?",
                    Integer.class,
                    customerId
            );
            if (deliveries != null && deliveries >= expectedDeliveries
                    && notifications != null && notifications >= expectedNotifications) {
                return;
            }
            Thread.sleep(100L);
        }
    }

    private void awaitAuditLog(UUID customerId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE customer_id = ? AND action = 'WEBHOOK_DELIVERY_REPLAY'",
                    Integer.class,
                    customerId
            );
            if (count != null && count >= 1) {
                return;
            }
            Thread.sleep(100L);
        }
    }
}
