package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void verifiesModularStructureAndAcyclicDependencies() {
        ApplicationModules modules = ApplicationModules.of(MeterFlowApplication.class);
        modules.verify();

        assertThat(modules.getModuleByName("shared")).isPresent();
        assertThat(modules.getModuleByName("identity")).isPresent();
        assertThat(modules.getModuleByName("portal")).isPresent();
    }
}
