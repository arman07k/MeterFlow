@org.springframework.modulith.ApplicationModule(
        displayName = "Payments and Dunning",
        allowedDependencies = { "shared", "identity :: api", "subscriptions :: api", "billing :: api" }
)
package com.meterflow.payments;
