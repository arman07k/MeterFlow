package com.meterflow.webhooks.internal;

import com.meterflow.webhooks.api.WebhookDeliveryFacade;
import com.meterflow.webhooks.api.WebhookDispatchSummary;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookConfiguration {

    @Bean
    HttpClient webhookHttpClient(WebhookProperties properties) {
        int timeoutMs = Math.max(1000, properties.getTimeoutMs());
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Component
    @ConditionalOnProperty(prefix = "meterflow.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class WebhookDeliveryScheduler {

        private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryScheduler.class);
        private final WebhookDeliveryFacade deliveryFacade;

        WebhookDeliveryScheduler(WebhookDeliveryFacade deliveryFacade) {
            this.deliveryFacade = deliveryFacade;
        }

        @Scheduled(fixedDelayString = "${meterflow.webhooks.worker-fixed-delay-ms:15000}")
        void pollDueDeliveries() {
            WebhookDispatchSummary summary = deliveryFacade.dispatchDueDeliveries();
            if (summary.claimedCount() > 0) {
                log.info(
                        "Webhook dispatch cycle: claimed={}, delivered={}, retrying={}, exhausted={}",
                        summary.claimedCount(),
                        summary.deliveredCount(),
                        summary.scheduledRetryCount(),
                        summary.exhaustedCount()
                );
            }
        }
    }
}
