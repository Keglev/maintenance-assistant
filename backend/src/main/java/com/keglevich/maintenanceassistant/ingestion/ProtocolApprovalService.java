package com.keglevich.maintenanceassistant.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Approval: a reviewer vouching for a protocol, and the four-eyes rule that keeps it meaningful.
 *
 * <p><b>What approval is, and what it is not.</b> It is a statement that a named person read this
 * protocol and stands behind it. It is <em>not</em> a gate on retrieval: an unapproved protocol is
 * still searchable and still citable, by decision of 2026-08-11 — "the admin may not review at a
 * weekend, and the factory does not stop". The newest knowledge in a plant is exactly the knowledge
 * that has not been reviewed yet, and hiding it until someone signs it off would withhold the
 * protocol about the fault happening right now.
 *
 * <p>The trade-off is accepted rather than hidden: an unapproved protocol is less reliable to
 * troubleshoot from. That is why the state is returned everywhere a protocol is — the moderation
 * list, the caller's own uploads, and the citations on an answer — so the interface can always say
 * which kind a reader is looking at. An unmarked unreviewed source would be the worst of both
 * worlds: NFR-2's citation discipline makes a cited claim look checked, and this flag is what keeps
 * that impression honest.
 *
 * <p><b>FOUR EYES: THE ROLE SPLIT IS THE RULE, THIS CHECK IS THE BELT.</b> Decision 3 of 2026-08-11
 * reads: author is never the corrector, corrector is never the approver. Since Carlos's decision of
 * 2026-08-13 the roles say it outright — the Techniker files, the Schichtleiter corrects, the Admin
 * approves, and <em>nobody holds two of those jobs</em> — so the primary guard is the
 * {@code @PreAuthorize} on each endpoint.
 *
 * <p>This check stays anyway, and deliberately. It is the only guard that can express "the same
 * HUMAN filed and approved this", which a role cannot; it is the only one that survives a future
 * role widening, and a rule enforced in exactly one place is one annotation edit away from being
 * gone. Whoever filed a protocol, and whoever last corrected it, may not be the one who approves it
 * — checked against the protocol's own history. The cost is one query on an act that happens rarely.
 *
 * <p><b>DUPLICATE DETECTION WARNS AND NEVER BLOCKS (2026-08-14).</b> Approving runs
 * {@link ProtocolSimilarityService} against the same machine's protocols and records what it found
 * in the ledger — and does nothing else with it. There is no branch below that refuses an approval
 * on a similarity score, and there is not meant to be one: the four E-47 protocols are four
 * different root causes behind one fault code, all legitimate, and any threshold sensitive enough
 * to catch a real duplicate would flag them as copies of each other. The admin decides; the system
 * informs, and then puts on the record that it informed.
 */
@Service
public class ProtocolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolApprovalService.class);

    public static final String APPROVED = "APPROVED";
    public static final String UNAPPROVED = "UNAPPROVED";

    /** The caller wrote or last corrected this protocol, so they may not also vouch for it. */
    public static final String FOUR_EYES_REQUIRED = "FOUR_EYES_REQUIRED";
    /** Approving something that is in the archive. */
    public static final String PROTOCOL_ARCHIVED = ProtocolEditService.PROTOCOL_ARCHIVED;

    private final JdbcClient jdbc;
    private final ProtocolModerationService moderation;
    private final ProtocolSimilarityService similarity;

    ProtocolApprovalService(JdbcClient jdbc, ProtocolModerationService moderation,
                            ProtocolSimilarityService similarity) {
        this.jdbc = jdbc;
        this.moderation = moderation;
        this.similarity = similarity;
    }

    /** The state of one protocol's approval, as the API returns it. */
    public record Approval(String state, String approvedBy, OffsetDateTime approvedAt) {

        public boolean approved() {
            return APPROVED.equals(state);
        }
    }

    /**
     * Approves or un-approves a protocol.
     *
     * <p><b>Idempotent, and deliberately silent about a no-op.</b> Approving something already
     * approved answers success and writes nothing: the request asserts a state rather than
     * performing a transition, so a double-clicked button is not an error and does not deserve a
     * second row in the ledger. The ledger stays a record of CHANGES — one row per moment the
     * corpus's trust actually moved — which is what makes it worth reading. Refusing the second
     * call was the alternative, and it would turn a harmless repeat into a support question.
     *
     * <p><b>A comment is required to withdraw approval and optional to grant it</b>, and the
     * asymmetry is the point. Approving affirms the text as it stands; un-approving takes back
     * something a named person already vouched for, and the next reader is owed the reason. It is
     * the same rule edit and delete follow, applied to the act that removes trust.
     *
     * @return empty if there is no such protocol
     */
    @Transactional
    public Optional<Approval> setApproval(UUID protocolId, boolean approve, String actor,
                                          String comment) {
        Optional<Target> found = load(protocolId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Target target = found.get();

        if (target.deletedAt() != null) {
            // 409, like editing an archived protocol: it exists, the same administrator can read it
            // in the archive, and "no such protocol" would be contradicted by their own screen.
            // Archived is final — vouching for something withdrawn from the corpus means nothing.
            throw new ProtocolModerationService.InvalidModerationRequestException(PROTOCOL_ARCHIVED,
                    "this protocol is in the archive; approving it would vouch for something no "
                            + "answer can cite");
        }

        boolean alreadyInState = approve == APPROVED.equals(target.approvalState());
        if (alreadyInState) {
            return Optional.of(target.asApproval());
        }

        if (approve) {
            requireFourEyes(protocolId, target, actor);
        } else {
            // Withdrawing trust is a change to how the corpus reads, and it is the direction that
            // needs explaining.
            comment = ProtocolModerationService.requireComment(comment);
        }

        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                        UPDATE protocol
                        SET approval_state = :state,
                            approved_by    = :approvedBy,
                            approved_at    = :approvedAt,
                            updated_at     = :now
                        WHERE id = :id
                        """)
                .param("id", protocolId)
                .param("state", approve ? APPROVED : UNAPPROVED)
                .param("approvedBy", approve ? actor : null, java.sql.Types.VARCHAR)
                .param("approvedAt", approve ? now : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", now)
                .update();

        moderation.recordEvent(protocolId, approve ? "APPROVE" : "UNAPPROVE", actor,
                approve ? approvalComment(protocolId, comment) : comment);

        // INFO for the same reason deletion is: this is the audit trail of the audit function, and
        // the queryable half lives in moderation_event.
        log.info("Moderation: {} {} protocol {} ('{}', machine {})",
                actor, approve ? "approved" : "un-approved", protocolId, target.title(),
                target.machineNo());

        return Optional.of(approve
                ? new Approval(APPROVED, actor, now)
                : new Approval(UNAPPROVED, null, null));
    }

    /** The prefix an auditor can grep the ledger for. Stable text, like an error code. */
    public static final String APPROVED_DESPITE = "approved despite ";

    /**
     * What the ledger says about an approval, including the fact that similar protocols existed.
     *
     * <p><b>Why this is on the record at all.</b> A plant auditor asking "did anybody notice that
     * these two protocols say the same thing?" should find the answer in the ledger rather than in
     * somebody's memory of a screen. It is the accountability argument ADR-006 already makes for
     * delete-with-reason, applied to the moment a protocol becomes vouched-for: an informed decision
     * and an uninformed one look identical afterwards unless one of them is written down.
     *
     * <p><b>The similarity is recomputed here rather than taken from the request</b>, and that is the
     * point. If the client sent the ids it happened to display, an approval made through {@code curl}
     * — or by a client that simply never asked — would record "nothing similar", which is the one
     * answer the ledger must never give wrongly. The check belongs to the act, not to the screen.
     *
     * <p><b>NO JUSTIFICATION IS REQUIRED, deliberately.</b> A mandatory text field on every approval
     * would make the common case — nothing similar, nothing to say — expensive, and a field people
     * are forced to fill fills up with "ok". The record that the approver was informed and proceeded
     * is the accountability that was missing; a sentence about it is not.
     *
     * <p>It stays ONE row. The ledger is a record of changes to the corpus's trust, and an approval
     * is one change however much context it carries; a second SIMILARITY row would be an event that
     * did not happen.
     */
    private String approvalComment(UUID protocolId, String comment) {
        ProtocolSimilarityService.SimilarityReport report = similarity.findSimilar(protocolId);
        String note = report.any()
                ? APPROVED_DESPITE + report.total() + " similar protocol(s) on this machine: "
                        + report.allIds().stream().map(UUID::toString)
                                .collect(java.util.stream.Collectors.joining(", "))
                : null;

        String stated = comment == null || comment.isBlank() ? null : comment.trim();
        if (stated != null && note != null) {
            // The human's words first, the system's observation after: a reader of the ledger is
            // looking for what the approver said, and a machine-generated clause in front of it
            // reads as though the system did the approving.
            return stated + " — " + note;
        }
        if (stated != null) {
            return stated;
        }
        return note != null ? note : "approved without further comment";
    }

    /**
     * The similarity report for one protocol, for the approver who is about to decide.
     *
     * <p>Exposed as its own read so the interface can ask <em>before</em> the approval rather than
     * learn about it afterwards. It runs the same code the approval itself runs, so what the screen
     * showed and what the ledger recorded cannot disagree.
     */
    public ProtocolSimilarityService.SimilarityReport similarTo(UUID protocolId) {
        return similarity.findSimilar(protocolId);
    }

    /**
     * Refuses an approval by the person who wrote or last corrected the protocol.
     *
     * <p>Checked against the ledger rather than against a role, because roles cannot express it:
     * the rule is about the same HUMAN doing both to the same protocol. {@code uploaded_by} covers
     * the author; the newest EDIT event covers the corrector. Both are Keycloak usernames, which is
     * why the two columns were written in one alphabet in the first place.
     *
     * <p><b>NEITHER BRANCH IS REACHABLE THROUGH THE API AS IT STANDS TODAY, and that is the role
     * split working rather than dead code.</b> Approval is admin-only; upload is Techniker and
     * Schichtleiter; correction has been Schichtleiter-only since 2026-08-13. An administrator can
     * therefore never be the author and never be the corrector, so no request this API accepts can
     * make either condition true. (Checked while looking for a way to demonstrate the refusal by
     * clicking — see the 2026-08-14 note in ADR-006; there is none, and adding a second demo
     * administrator would not create one.)
     *
     * <p>It is KEPT rather than deleted. It is the belt to the role split's braces: it is the only
     * guard that could ever express "the same HUMAN did both", it is the one that survives a future
     * widening of any of those three annotations, and a rule enforced in exactly one place is one
     * edit away from being gone. See the class javadoc.
     */
    private void requireFourEyes(UUID protocolId, Target target, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalStateException("an approval must carry an actor");
        }
        if (actor.equalsIgnoreCase(target.uploadedBy())) {
            throw new ProtocolModerationService.InvalidModerationRequestException(FOUR_EYES_REQUIRED,
                    "you filed this protocol, so you cannot also approve it: approval is a second "
                            + "pair of eyes, and it has to belong to someone else");
        }
        Optional<String> lastEditor = jdbc.sql("""
                        SELECT actor FROM moderation_event
                        WHERE protocol_id = :id AND action = 'EDIT'
                        ORDER BY created_at DESC, id
                        LIMIT 1
                        """)
                .param("id", protocolId)
                .query(String.class)
                .optional();
        if (lastEditor.filter(actor::equalsIgnoreCase).isPresent()) {
            throw new ProtocolModerationService.InvalidModerationRequestException(FOUR_EYES_REQUIRED,
                    "you corrected this protocol, so you cannot also approve it: the corrector is "
                            + "never the approver");
        }
    }

    /** The approval state of one protocol, for the read paths. */
    public Optional<Approval> find(UUID protocolId) {
        return load(protocolId).map(Target::asApproval);
    }

    private Optional<Target> load(UUID protocolId) {
        return jdbc.sql("""
                        SELECT p.title, p.uploaded_by, p.deleted_at,
                               p.approval_state, p.approved_by, p.approved_at, m.machine_no
                        FROM protocol p JOIN machine m ON m.id = p.machine_id
                        WHERE p.id = :id
                        """)
                .param("id", protocolId)
                .query((rs, rowNum) -> new Target(
                        rs.getString("title"),
                        rs.getString("uploaded_by"),
                        rs.getObject("deleted_at", OffsetDateTime.class),
                        rs.getString("approval_state"),
                        rs.getString("approved_by"),
                        rs.getObject("approved_at", OffsetDateTime.class),
                        rs.getString("machine_no")))
                .optional();
    }

    private record Target(String title, String uploadedBy, OffsetDateTime deletedAt,
                          String approvalState, String approvedBy, OffsetDateTime approvedAt,
                          String machineNo) {

        Approval asApproval() {
            return new Approval(approvalState, approvedBy, approvedAt);
        }
    }
}
