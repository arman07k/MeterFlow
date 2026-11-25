@org.springframework.modulith.ApplicationModule(
        displayName = "Billing, Credits, and Simulation",
        allowedDependencies = { "shared", "identity :: api", "catalog :: api", "subscriptions :: api", "metering :: api" }
)
package com.meterflow.billing;
