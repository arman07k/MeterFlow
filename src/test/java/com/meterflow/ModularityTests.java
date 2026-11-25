package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModularStructureAcyclicDependenciesAndModuleEncapsulation() {
        ApplicationModules modules = ApplicationModules.of(MeterFlowApplication.class);
        modules.verify();

        assertThat(modules.getModuleByName("shared")).isPresent();
        assertThat(modules.getModuleByName("identity")).isPresent();
        assertThat(modules.getModuleByName("catalog")).isPresent();
        assertThat(modules.getModuleByName("subscriptions")).isPresent();
        assertThat(modules.getModuleByName("metering")).isPresent();
        assertThat(modules.getModuleByName("billing")).isPresent();
        assertThat(modules.getModuleByName("payments")).isPresent();
        assertThat(modules.getModuleByName("webhooks")).isPresent();
        assertThat(modules.getModuleByName("notifications")).isPresent();
        assertThat(modules.getModuleByName("portal")).isPresent();

        ApplicationModule identityModule = modules.getModuleByName("identity").orElseThrow();
        assertThat(identityModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(identityModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule catalogModule = modules.getModuleByName("catalog").orElseThrow();
        assertThat(catalogModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(catalogModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule subscriptionsModule = modules.getModuleByName("subscriptions").orElseThrow();
        assertThat(subscriptionsModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(subscriptionsModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule meteringModule = modules.getModuleByName("metering").orElseThrow();
        assertThat(meteringModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(meteringModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule billingModule = modules.getModuleByName("billing").orElseThrow();
        assertThat(billingModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(billingModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule paymentsModule = modules.getModuleByName("payments").orElseThrow();
        assertThat(paymentsModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(paymentsModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule webhooksModule = modules.getModuleByName("webhooks").orElseThrow();
        assertThat(webhooksModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(webhooksModule.getNamedInterfaces().getByName("api")).isPresent();

        ApplicationModule notificationsModule = modules.getModuleByName("notifications").orElseThrow();
        assertThat(notificationsModule.getNamedInterfaces().hasExplicitInterfaces()).isTrue();
        assertThat(notificationsModule.getNamedInterfaces().getByName("api")).isPresent();
    }
}
