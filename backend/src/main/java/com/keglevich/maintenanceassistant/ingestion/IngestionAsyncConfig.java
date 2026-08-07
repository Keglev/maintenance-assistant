package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The thread pool indexing runs on.
 *
 * <p>Its own pool, not the common one. NFR-4 makes upload confirmation immediate, so the HTTP
 * thread must return a 202 and hand the work over; if indexing shared the request threads, a
 * provider that is slow today would make uploads slow today.
 *
 * <p>The pool size is also the provider concurrency: every worker holds one embedding call. Small
 * on purpose — the corpus is 150 documents, not a stream, and four parallel callers is politeness
 * toward a shared gateway rather than a throughput target.
 */
@Configuration
@EnableAsync
class IngestionAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestionAsyncConfig.class);

    static final String EXECUTOR = "ingestionExecutor";

    @Bean(EXECUTOR)
    Executor ingestionExecutor(IngestionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("ingest-");
        // The queue is bounded, so a backlog run larger than it has to do something. Running the
        // overflow on the calling thread throttles the submitter instead of dropping protocols —
        // for a backlog that means it simply takes longer, which is the right answer.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let an in-flight embedding finish on shutdown rather than leaving a protocol charged for
        // and unwritten.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("Ingestion executor: {} workers, queue {}", properties.workers(), properties.queueCapacity());
        return executor;
    }
}
