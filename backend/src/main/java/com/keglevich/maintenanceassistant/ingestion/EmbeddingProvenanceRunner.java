package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The operator's front door to {@link EmbeddingProvenanceVerifier} — a one-shot check, off by
 * default.
 *
 * <p><b>Why a runner and not an endpoint.</b> An HTTP endpoint would be a permanent surface on a
 * public deployment that spends provider money when it is called, needs a role rule, a rate limit
 * and a controller test, and would be reachable by anyone holding an administrator token — all of
 * that for a diagnostic run perhaps twice a year. A runner is reached only by someone who can
 * already start a container on the host, which is the same person the runbook is written for, and it
 * adds nothing to the running application: the bean does not exist unless the property is set.
 *
 * <pre>
 *   docker compose -f docker-compose.prod.yml run --rm \
 *     -e MAINTENANCE_OPS_VERIFY_EMBEDDINGS=true backend
 * </pre>
 *
 * <p>It exits non-zero when the index is not the configured model's, so it can be read by a script
 * and cannot be mistaken for success in a scrollback. See {@code docs/runbooks/} for the procedure
 * this belongs to.
 *
 * <p><b>It never runs in the serving application.</b> The property is absent from every deployed
 * configuration, and this runs at startup rather than per request, so a normal restart does no
 * provider work.
 *
 * <p><b>COVERAGE WAIVER</b> (2026-08-22, register in docs/REFACTOR-STANDARDS.txt). This class is at
 * 0% and stays there. What it contains is flag-gated wiring: read a report, log it, choose an exit
 * code. The mechanism it invokes — {@link EmbeddingProvenanceVerifier} — is covered by
 * EmbeddingProvenanceVerifierIT, so the part that can be wrong about vectors IS tested; what is
 * untested here is a conditional bean and two log lines. Covering it would mean a context that
 * enables an operational tool and then asserting on its console output, which tests the harness
 * rather than the tool. It is exercised operationally instead: it was run against production on
 * 2026-08-19, which is the evidence that matters for a runbook step.
 */
@Component
@ConditionalOnProperty(prefix = "maintenance.ops", name = "verify-embeddings", havingValue = "true")
// After the seed and backlog runners: run in the same startup as a seeding operation and the useful
// answer is about what they just wrote, not about what was there before them.
@Order(100)
class EmbeddingProvenanceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProvenanceRunner.class);

    private final EmbeddingProvenanceVerifier verifier;
    private final ApplicationContext context;
    private final int sampleSize;

    EmbeddingProvenanceRunner(
            EmbeddingProvenanceVerifier verifier,
            ApplicationContext context,
            @Value("${maintenance.ops.verify-embeddings-sample:0}") int sampleSize) {
        this.verifier = verifier;
        this.context = context;
        this.sampleSize = sampleSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        EmbeddingProvenanceVerifier.Report report = verifier.verify(sampleSize);
        // System.out rather than the logger: this is a report a person asked for and reads, not an
        // event in the application's life, and it must survive whatever the log level is set to.
        System.out.println(report.describe());

        if (report.clean()) {
            log.info("Embedding provenance: {} chunks checked, all written by the configured model",
                    report.probes().size());
        } else {
            log.error("Embedding provenance: {} of {} chunks were written by a different embedding "
                    + "model", report.foreign().size(), report.probes().size());
        }

        // STOP, in both outcomes. This is a command, not a server: the operator runs it as
        // `docker compose run --rm` and needs the container to end and to carry a status. The first
        // draft only ended on failure — because an exception stops the context — so a HEALTHY index
        // left the container running forever, which is the one outcome nobody watches. A diagnostic
        // that hangs when it has good news is a diagnostic people stop running.
        //
        // The exit code is the answer: 0 clean, 1 foreign vectors found, so the runbook's step can
        // be read by a script and cannot be mistaken for success in a scrollback.
        int code = report.clean() ? 0 : 1;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
