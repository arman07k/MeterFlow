package com.meterflow.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
