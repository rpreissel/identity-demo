package com.example.identity.core.account.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

internal const val PERSON_CHANGE_EXECUTOR = "personChangeExecutor"

/**
 * One thread for the directory's change events: two changes to the same person must be applied in
 * the order the directory published them, and the Event Publication Registry guarantees delivery,
 * not order. `spring.task.execution.mode: force` in application.yml keeps every other `@Async` off
 * this single thread.
 */
@Configuration
class PersonChangeExecutorConfig {

    @Bean(PERSON_CHANGE_EXECUTOR)
    fun personChangeExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        setThreadNamePrefix("person-change-")
    }
}
