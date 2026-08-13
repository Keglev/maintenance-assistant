package com.keglevich.maintenanceassistant.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Duplicate detection: how similar two protocols on one machine have to be before an approver is
 * told about it.
 *
 * <p><b>The threshold is a property and never a literal</b>, for the reason ADR-002 gives about the
 * query threshold: an absolute cosine value is a property of the embedding model and of the corpus,
 * not of the problem. Swap {@code bge-m3} for anything else and this number is meaningless until it
 * has been measured again. {@code DuplicateSimilarityCalibrationIT} is how it is measured.
 *
 * <p><b>It is not the query threshold, and the two numbers are not comparable.</b> ADR-002's 0.55 is
 * question-to-chunk: a short interrogative sentence against a paragraph of a maintenance report, two
 * texts that share a topic and nothing else. This is document-to-document, between two texts of the
 * same genre, on the same machine, written from the same template, both carrying the same
 * {@code "PR-03 · E-47 · …"} context prefix the chunker adds. Everything here starts high — the
 * measured mean over all 1,393 same-machine pairs in the corpus is 0.55, which is where the query
 * path's <em>threshold</em> sits. Carrying 0.55 over would flag roughly half the corpus.
 *
 * @param similarityThreshold at or above this, a protocol is offered to the approver as something
 *                            worth comparing. WARNS, NEVER BLOCKS — no code path refuses an approval
 *                            on this number, and that is the governing rule of the whole feature.
 * @param maxCandidates       how many are shown. Three, because this is a prompt to compare rather
 *                            than a report to read: an approver who is handed ten links reads none
 *                            of them, and the ranking means the three most similar are the three
 *                            worth opening. The count of everything above the threshold is still
 *                            reported, so a long tail is never silently dropped.
 */
@ConfigurationProperties(prefix = "maintenance.duplicates")
public record DuplicateProperties(double similarityThreshold, int maxCandidates) {
}
