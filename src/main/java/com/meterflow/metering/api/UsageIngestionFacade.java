package com.meterflow.metering.api;

public interface UsageIngestionFacade {

    int MAX_BATCH_SIZE = 1_000;

    BatchIngestionResult ingestBatch(IngestUsageBatchCommand command);

    EventIngestionItemResult ingestSingle(IngestUsageEventCommand command);
}
