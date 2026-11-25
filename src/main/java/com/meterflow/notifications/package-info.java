@org.springframework.modulith.ApplicationModule(
        displayName = "Notifications and Audit",
        allowedDependencies = {
                "shared",
                "identity :: api",
                "billing :: api",
                "payments :: api",
                "metering :: api",
                "webhooks :: api"
        }
)
package com.meterflow.notifications;
