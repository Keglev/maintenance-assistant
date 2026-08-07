package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs the backlog once at startup, when asked to.
 *
 * <p>Off by default, and that is the point: indexing spends money. A deployment restarting for an
 * unrelated reason must not re-embed anything, and it will not — protocols already at
 * {@code INDEXED} are not selected. This exists so that seeding a fresh environment is two flags
 * rather than a manual API call, and so the same operation is available as an admin endpoint for
 * everything after the first time.
 */
@Component
@ConditionalOnProperty(prefix = "maintenance.ingestion", name = "backlog-on-startup", havingValue = "true")
class IngestionBacklogRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionBacklogRunner.class);

    private final IngestionBacklogService backlog;

    IngestionBacklogRunner(IngestionBacklogService backlog) {
        this.backlog = backlog;
    }

    @Override
    public void run(ApplicationArguments args) {
        int enqueued = backlog.enqueue(List.of("RECEIVED"), null);
        log.info("Startup backlog enqueued {} protocols; indexing continues in the background", enqueued);
    }
}
