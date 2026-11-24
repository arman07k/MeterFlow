@org.springframework.modulith.ApplicationModule(
        displayName = "Payments and Dunning",
        allowedDependencies = { "shared", "billing :: api" }
)
package com.meterflow.payments;
