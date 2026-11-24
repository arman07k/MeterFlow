package com.meterflow.portal.internal;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 specification foundation for MeterFlow /api/v1/** endpoints.
 */
@Configuration
public class OpenApiConfiguration {

    public static final String BASIC_AUTH_SCHEME = "basicAuth";

    @Bean
    public OpenAPI meterFlowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MeterFlow API")
                        .version("0.1.0-SNAPSHOT")
                        .description("Usage-Based Metering, Subscription Rating, Invoicing, Dunning, and Webhook Platform API"))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BASIC_AUTH_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")));
    }
}
