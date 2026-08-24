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
 *
 * <p><b>COVERAGE WAIVER</b> (2026-08-22, register in docs/REFACTOR-STANDARDS.txt). This class is at
 * 0% and stays there. It is a conditional bean that calls {@code enqueue} once and logs the count —
 * it carries no branches of its own. The work it delegates to is covered where the work lives
 * (IngestionBacklogService, IngestionPipelineIT), and the same operation is reachable through the
 * admin endpoint, which is covered as a web slice. A test here could only assert that a startup
 * hook calls a method, which is the wiring restating itself.
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
