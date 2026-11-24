package com.meterflow.shared;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;

/**
 * Retry sweeper for incomplete PostgreSQL transactional outbox records (C8, ADR-004).
 * Always registered in full application contexts; periodic execution is wired by
 * {@link SchedulingConfiguration} when {@code meterflow.scheduling.enabled=true}.
 */
@Component
public class OutboxRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetryScheduler.class);

    private final IncompleteEventPublications incompleteEventPublications;
    private final Duration minimumAge;

    public OutboxRetryScheduler(
            IncompleteEventPublications incompleteEventPublications,
            @Value("${meterflow.outbox.retry-min-age-seconds:10}") long minAgeSeconds
    ) {
        this.incompleteEventPublications = incompleteEventPublications;
        this.minimumAge = Duration.ofSeconds(Math.max(1, minAgeSeconds));
    }

    public Duration minimumAge() {
        return minimumAge;
    }

    public void resubmitIncompleteEvents() {
        log.debug("Checking PostgreSQL outbox for incomplete event publications older than {}", minimumAge);
        incompleteEventPublications.resubmitIncompletePublicationsOlderThan(minimumAge);
    }
}
