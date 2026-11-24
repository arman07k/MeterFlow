@org.springframework.modulith.ApplicationModule(
        displayName = "Web Portal and API Adapter",
        allowedDependencies = {
                "shared",
                "identity :: api",
                "catalog :: api",
                "subscriptions :: api",
                "metering :: api",
                "billing :: api",
                "payments :: api",
                "webhooks :: api",
                "notifications :: api"
        }
)
package com.meterflow.portal;
