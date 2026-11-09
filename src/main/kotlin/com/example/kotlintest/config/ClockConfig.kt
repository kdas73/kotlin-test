package com.example.kotlintest.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The application clock. Inject it instead of calling `Instant.now()` so time can be controlled in tests. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
