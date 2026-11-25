package com.meterflow.metering.api;

import java.util.List;

public record IngestUsageBatchCommand(
        List<IngestUsageEventCommand> events
) {
    public IngestUsageBatchCommand {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
