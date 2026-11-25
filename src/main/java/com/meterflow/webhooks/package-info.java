@org.springframework.modulith.ApplicationModule(
        displayName = "Outbound Webhooks",
        allowedDependencies = { "shared", "identity :: api", "billing :: api", "payments :: api", "metering :: api" }
)
package com.meterflow.webhooks;
