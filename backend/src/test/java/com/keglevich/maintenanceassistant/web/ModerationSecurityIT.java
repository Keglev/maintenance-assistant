package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolApprovalService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolEditService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolSimilarityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may moderate. This is the feature's actual boundary, so it gets its own suite.
 *
 * <p>The interesting assertions are the refusals, and v1.2 changed which ones they are. A
 * Schichtleiter still may not delete, may not read the archive and — the one that matters most now
 * — <b>may not approve</b>: they became the corrector in the trust chain, so letting them also
 * approve would put the corrector and the approver back in the same pair of hands. What they may do
 * is correct, which is the whole point of decision 3 of 2026-08-11.
 *
 * <p>The Techniker may write and may never fix, not even their own protocol. That is asserted here
 * rather than assumed, because "may create" and "may fix what they created" are the same permission
 * in most systems and deliberately are not in this one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModerationSecurityIT {

    private static final UUID PROTOCOL = UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");
    /** The protocol the report points AT — a second id, so a mix-up cannot pass. */
    private static final UUID OTHER = UUID.fromString("0f9c5b02-0000-4000-8000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private ProtocolModerationService moderation;

    @MockitoBean
    private ProtocolEditService edits;

    @MockitoBean
    private ProtocolDocumentService documents;

    @MockitoBean
    private ProtocolApprovalService approvals;

    // ---------------------------------------------------------------------------------------
    // Refused
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "a {0} may not list the corpus")
    // THE SCHICHTLEITER CAME OFF THIS LIST ON 2026-08-14, and it is the narrowest widening that
    // makes their one write reachable: they have been the only role that may correct since
    // 2026-08-13, and until now there was no way for them to FIND the protocol to correct. An
    // Operator and a Techniker are still refused — neither corrects anything.
    @ValueSource(strings = {"operator", "techniker"})
    void noShopFloorRoleMayList(String role) throws Exception {
        mockMvc.perform(get("/api/moderation/protocols").with(as(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "a {0} may not read a protocol through the moderation path")
    // Same change, same reason: the edit dialog loads the STORED TEXT rather than reconstructing it
    // from the row, so a corrector who cannot read this file cannot safely rewrite it.
    @ValueSource(strings = {"operator", "techniker"})
    void noShopFloorRoleMayReadThroughModeration(String role) throws Exception {
        // The shop-floor document endpoint stays open to them; this one is not it. Reading for
        // review or for correction is not the same act as reading to check a citation.
        mockMvc.perform(get("/api/moderation/protocols/{id}/document", PROTOCOL).with(as(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a Schichtleiter MAY list the corpus — they cannot correct what they cannot find")
    void theCorrectorMayList() throws Exception {
        when(moderation.list(anyInt(), anyInt(), any())).thenReturn(
                new ProtocolModerationService.ProtocolPage(List.of(
                        new ProtocolModerationService.ModeratedProtocol(
                                PROTOCOL, "PR-03", "E-47 Druckabfall", "STOERUNG", "E-47",
                                "techniker", OffsetDateTime.now(), "INDEXED", 2,
                                new ProtocolApprovalService.Approval("UNAPPROVED", null, null))),
                        0, 10, 1));

        mockMvc.perform(get("/api/moderation/protocols").with(as("schichtleiter")))
                .andExpect(status().isOk())
                // The author travels with the row for this reader too. A corrector who cannot see
                // who filed a protocol is correcting an anonymous claim.
                .andExpect(jsonPath("$.items[0].uploadedBy").value("techniker"))
                // And the approval state, because correcting an APPROVED protocol knocks it out of
                // approval (#53) and the person doing it is owed that warning.
                .andExpect(jsonPath("$.items[0].approval.state").value("UNAPPROVED"));
    }

    @Test
    @DisplayName("a Schichtleiter MAY read a protocol's document — the edit dialog loads it")
    void theCorrectorMayReadTheDocument() throws Exception {
        when(documents.find(PROTOCOL)).thenReturn(Optional.of(
                new ProtocolDocumentService.ProtocolDocument(
                        new org.springframework.core.io.ByteArrayResource("Symptom:\n".getBytes()),
                        "PR-03-E-47.txt", "text/plain;charset=UTF-8", 9)));

        mockMvc.perform(get("/api/moderation/protocols/{id}/document", PROTOCOL)
                        .with(as("schichtleiter")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "a Schichtleiter may not reach {0} — correcting is not moderating")
    @ValueSource(strings = {"delete", "approve", "archive", "archivedDocument", "similar"})
    void theCorrectorGetsTheCORRECTIONJobAndNothingElse(String act) throws Exception {
        // THE FENCE AROUND 2026-08-14's WIDENING, and the test that matters most in this file. Two
        // reads were opened so that one write could be reached; a Schichtleiter who can suddenly
        // delete, approve or read the archive of what was removed would be a worse defect than the
        // unreachable endpoint this change exists to fix.
        var request = switch (act) {
            case "delete" -> delete("/api/moderation/protocols/{id}", PROTOCOL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"weg damit\"}");
            case "approve" -> put("/api/moderation/protocols/{id}/approval", PROTOCOL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"approved\":true}");
            case "archive" -> get("/api/moderation/protocols/deleted");
            // The duplicate report. A corrector who could read it would be handed a signal with no
            // act attached: the decision it informs — approve, merge or reject — is the
            // administrator's, and correcting is not moderating.
            case "similar" -> get("/api/moderation/protocols/{id}/similar", PROTOCOL);
            default -> get("/api/moderation/protocols/deleted/{id}/document", PROTOCOL);
        };

        mockMvc.perform(request.with(as("schichtleiter"))).andExpect(status().isForbidden());

        // Refused before anything happened, not after.
        verify(moderation, never()).delete(any(), anyString(), anyString());
        verify(approvals, never()).setApproval(any(), anyBoolean(), anyString(), any());
    }

    @ParameterizedTest(name = "a {0} may not delete a protocol")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMayDelete(String role) throws Exception {
        mockMvc.perform(delete("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"weg damit\"}")
                        .with(as(role)))
                .andExpect(status().isForbidden());

        // Refused before anything happened, not after: a 403 that had already deleted the row
        // would be a status code, not a permission check.
        verify(moderation, never()).delete(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "a {0} may not edit a protocol")
    // THE SCHICHTLEITER IS NO LONGER ON THIS LIST, and that is the v1.2 trust chain rather than a
    // relaxation. Decision 3 of 2026-08-11 makes them the CORRECTOR: the Techniker writes and never
    // fixes their own work, the Schichtleiter corrects, the Admin approves — three people. While
    // correcting belonged to the admin alone, the corrector and the approver were the same role and
    // four eyes collapsed to two.
    //
    // THE ADMIN IS NOT ON THIS LIST EITHER, and only because they are not a shop-floor role — the
    // administrator's refusal has its own named test below, because it is a decision rather than the
    // absence of one.
    @ValueSource(strings = {"operator", "techniker"})
    void noShopFloorRoleMayEdit(String role) throws Exception {
        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Neu\",\"content\":\"Text\",\"comment\":\"korrigiert\"}")
                        .with(as(role)))
                .andExpect(status().isForbidden());

        verify(edits, never()).edit(any(), any(), anyString());
    }

    @Test
    @DisplayName("a Techniker may not edit even their OWN protocol — writing and fixing are not the same permission")
    void aTechnikerMayNotEditTheirOwnProtocol() throws Exception {
        // The rule that makes a correction worth something: it was made by a second person. There
        // is no "unless you wrote it" branch anywhere, and this test exists so nobody adds one as a
        // convenience — the endpoint cannot be reached by the role at all, whoever the author is.
        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Neu\",\"content\":\"Text\",\"comment\":\"eigenes protokoll\"}")
                        .with(as("techniker")))
                .andExpect(status().isForbidden());

        verify(edits, never()).edit(any(), any(), anyString());
    }

    @ParameterizedTest(name = "a {0} may not approve a protocol")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMayApprove(String role) throws Exception {
        // The Schichtleiter is the one that matters. They may now correct, and if they could also
        // approve then the corrector and the approver would be the same person again — which is
        // exactly what the chain exists to prevent.
        mockMvc.perform(put("/api/moderation/protocols/{id}/approval", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}")
                        .with(as(role)))
                .andExpect(status().isForbidden());

        verify(approvals, never()).setApproval(any(), anyBoolean(), anyString(), any());
    }

    @Test
    @DisplayName("a Schichtleiter MAY correct a protocol — they are the corrector in the chain")
    void aSchichtleiterMayEdit() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenReturn(Optional.of(PROTOCOL));

        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Neu\",\"content\":\"Text\",\"comment\":\"Drehmoment korrigiert\"}")
                        .with(as("schichtleiter")))
                .andExpect(status().isAccepted());

        verify(edits).edit(eq(PROTOCOL), any(), eq("schichtleiter"));
    }

    @Test
    @DisplayName("an ADMIN may NOT edit — the approver does not also correct (2026-08-13)")
    void anAdminMayNotEdit() throws Exception {
        // THE PERMISSION THIS PR REVERSES. #53 left the admin with the edit they had held since #39
        // and enforced four eyes on the ACT instead. Carlos chose the clean chain afterwards:
        // Techniker writes, Schichtleiter corrects, Admin approves, and nobody does two jobs. This
        // is that decision as a test — the one assertion that would go quiet if the annotation were
        // widened back by accident.
        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Neu\",\"content\":\"Text\",\"comment\":\"korrigiert\"}")
                        .with(as("admin")))
                .andExpect(status().isForbidden());

        // Refused before the service was reached, not after. The ledger check in
        // ProtocolApprovalService is still there and still tested, but it is the belt now: it would
        // only have caught this administrator LATER, at the approval, and only if they tried to
        // approve their own correction.
        verify(edits, never()).edit(any(), any(), anyString());
    }

    @ParameterizedTest(name = "a {0} may not read the duplicate report")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMayReadTheDuplicateReport(String role) throws Exception {
        mockMvc.perform(get("/api/moderation/protocols/{id}/similar", PROTOCOL).with(as(role)))
                .andExpect(status().isForbidden());

        verify(approvals, never()).similarTo(any());
    }

    @Test
    @DisplayName("an admin may read the duplicate report, and it carries each candidate's own approval state")
    void anAdminMayReadTheDuplicateReport() throws Exception {
        when(approvals.similarTo(PROTOCOL)).thenReturn(
                new ProtocolSimilarityService.SimilarityReport(true, List.of(
                        new ProtocolSimilarityService.SimilarProtocol(
                                OTHER, "E-47 Druckabfall im Presshub", LocalDate.of(2024, 10, 8),
                                "schichtleiter", OffsetDateTime.now(), 0.9305,
                                new ProtocolApprovalService.Approval("APPROVED", "admin",
                                        OffsetDateTime.now()))),
                        1, List.of(OTHER), 0.92));

        mockMvc.perform(get("/api/moderation/protocols/{id}/similar", PROTOCOL).with(as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparable").value(true))
                .andExpect(jsonPath("$.total").value(1))
                // The threshold travels with the answer so no screen and no test hard-codes a number
                // the configuration is free to change.
                .andExpect(jsonPath("$.threshold").value(0.92))
                .andExpect(jsonPath("$.candidates[0].similarity").value(0.9305))
                // The candidate's OWN state. "Nearly the same as something already vouched for" and
                // "nearly the same as something nobody has reviewed" are different situations, and
                // the interface cannot tell them apart without this.
                .andExpect(jsonPath("$.candidates[0].approval.state").value("APPROVED"));
    }

    @Test
    @DisplayName("an admin may approve")
    void anAdminMayApprove() throws Exception {
        when(approvals.setApproval(any(), anyBoolean(), anyString(), any()))
                .thenReturn(Optional.of(new ProtocolApprovalService.Approval(
                        "APPROVED", "admin", OffsetDateTime.now())));

        mockMvc.perform(put("/api/moderation/protocols/{id}/approval", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}")
                        .with(as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("admin"));

        verify(approvals).setApproval(eq(PROTOCOL), eq(true), eq("admin"), any());
    }

    @ParameterizedTest(name = "a {0} may not read the archive")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMaySeeTheArchive(String role) throws Exception {
        // The archive holds exactly the protocols someone decided were not fit to be read.
        mockMvc.perform(get("/api/moderation/protocols/deleted").with(as(role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/moderation/protocols/deleted/{id}/document", PROTOCOL).with(as(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "{0} requires a token")
    @ValueSource(strings = {
            "/api/moderation/protocols",
            "/api/moderation/protocols/0f9c5b02-0000-4000-8000-000000000001/document"
    })
    void moderationRequiresAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("deleting without a token is a 401, not a 404")
    void deletingRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/moderation/protocols/{id}", PROTOCOL))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------------------------
    // Allowed
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an admin lists the corpus, paged, with the author of each protocol")
    void anAdminMayList() throws Exception {
        when(moderation.list(anyInt(), anyInt(), any())).thenReturn(new ProtocolModerationService.ProtocolPage(
                List.of(new ProtocolModerationService.ModeratedProtocol(
                        PROTOCOL, "PR-03", "E-47 Druckabfall", "STOERUNG", "E-47",
                        "schichtleiter", OffsetDateTime.now(), "INDEXED", 2,
                        new ProtocolApprovalService.Approval("APPROVED", "admin", OffsetDateTime.now()))),
                0, 10, 151));

        mockMvc.perform(get("/api/moderation/protocols").param("page", "0").param("size", "10")
                        .with(as("admin")))
                .andExpect(status().isOk())
                // uploaded_by is the accountability half of ADR-006 — without it on screen, a
                // reviewer can see a bad protocol but not who filed it.
                .andExpect(jsonPath("$.items[0].uploadedBy").value("schichtleiter"))
                .andExpect(jsonPath("$.items[0].chunkCount").value(2))
                .andExpect(jsonPath("$.total").value(151));
    }

    @Test
    @DisplayName("an admin deletes a protocol and gets 204, and the comment travels in the body")
    void anAdminMayDelete() throws Exception {
        when(moderation.delete(any(), anyString(), anyString())).thenReturn(true);

        mockMvc.perform(delete("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"erfundene Massnahme\"}")
                        .with(as("admin")))
                .andExpect(status().isNoContent());

        // The username, not the subject: the deletion log and the authorship it judges are written
        // in the same identity. The comment travels in a body rather than a query string, because a
        // sentence about a named colleague's mistake does not belong in an access log.
        verify(moderation).delete(PROTOCOL, "admin", "erfundene Massnahme");
    }

    @Test
    @DisplayName("deleting an unknown protocol is a 404")
    void deletingWhatIsNotThereIsNotFound() throws Exception {
        when(moderation.delete(any(), anyString(), anyString())).thenReturn(false);

        mockMvc.perform(delete("/api/moderation/protocols/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"weg damit\"}")
                        .with(as("admin")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a Schichtleiter edits a protocol and gets 202 — indexing is asynchronous")
    void theCorrectorMayEdit() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenReturn(Optional.of(PROTOCOL));

        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"E-47 Druckabfall","errorCode":"E-47",
                                 "content":"Symptom:\\nKein Druck.\\n","comment":"Drehmoment korrigiert"}
                                """)
                        .with(as("schichtleiter")))
                // 202 rather than 200, matching upload: the protocol is corrected when this
                // returns and it is not searchable again until the re-index finishes.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        // The actor recorded is the corrector, which is what the approval check later reads.
        verify(edits).edit(eq(PROTOCOL), any(), eq("schichtleiter"));
    }

    @Test
    @DisplayName("editing an unknown protocol is a 404")
    void editingWhatIsNotThereIsNotFound() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/moderation/protocols/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"C\",\"comment\":\"warum\"}")
                        .with(as("schichtleiter")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an admin reads the archive and an archived protocol's document")
    void anAdminMaySeeTheArchive() throws Exception {
        when(moderation.listDeleted(any(), anyInt(), anyInt())).thenReturn(
                new ProtocolModerationService.DeletedProtocolPage(List.of(
                        new ProtocolModerationService.ArchivedProtocol(
                                PROTOCOL, "PR-03", "E-47 Druckabfall", "STOERUNG", "E-47",
                                "schichtleiter", OffsetDateTime.now(), OffsetDateTime.now(),
                                "admin", "erfundene Massnahme")),
                        0, 10, 1, ProtocolModerationService.ARCHIVE_CAP));
        when(documents.findArchived(PROTOCOL)).thenReturn(Optional.of(
                new ProtocolDocumentService.ProtocolDocument(
                        new org.springframework.core.io.ByteArrayResource("Symptom:\n".getBytes()),
                        "PR-03-E-47.txt", "text/plain;charset=UTF-8", 9)));

        mockMvc.perform(get("/api/moderation/protocols/deleted").with(as("admin")))
                .andExpect(status().isOk())
                // The reason is the field the whole archive exists to carry.
                .andExpect(jsonPath("$.items[0].deleteComment").value("erfundene Massnahme"))
                .andExpect(jsonPath("$.items[0].deletedBy").value("admin"))
                .andExpect(jsonPath("$.cap").value(ProtocolModerationService.ARCHIVE_CAP));

        mockMvc.perform(get("/api/moderation/protocols/deleted/{id}/document", PROTOCOL)
                        .with(as("admin")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("there is no restore endpoint, and adding one should fail this test")
    void thereIsNoRestore() throws Exception {
        // Not a behaviour but a design decision, and it is worth a guard: undelete would turn the
        // archive into a staging area for putting bad protocols back (ADR-006 revision). Everything
        // under /api/moderation is admin-only, so an admin getting anything other than 404/405 here
        // means a route was added.
        mockMvc.perform(post("/api/moderation/protocols/{id}/restore", PROTOCOL).with(as("admin")))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status)
                            .as("a restore route appeared: %d", status)
                            .isIn(404, 405);
                });
    }

    @Test
    @DisplayName("an admin reads any protocol, holding no shop-floor role")
    void anAdminMayReadAnyDocument() throws Exception {
        when(documents.find(PROTOCOL)).thenReturn(Optional.of(new ProtocolDocumentService.ProtocolDocument(
                new org.springframework.core.io.ByteArrayResource("Symptom:\nKein Druck.\n".getBytes()),
                "PR-03-E-47.txt", "text/plain;charset=UTF-8", 21)));

        mockMvc.perform(get("/api/moderation/protocols/{id}/document", PROTOCOL).with(as("admin")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a protocol that is gone answers 404 on the moderation document path too")
    void aDeletedProtocolHasNoDocument() throws Exception {
        when(documents.find(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/moderation/protocols/{id}/document", PROTOCOL).with(as("admin")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return authentication(jwtAuthenticationConverter.convert(keycloakToken(role, role)));
    }

    private static Jwt keycloakToken(String username, String... realmRoles) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("iss", "http://localhost:8081/realms/maintenance")
                .claim("aud", List.of("backend"))
                .claim("azp", "frontend")
                .claim("sub", "00000000-0000-0000-0000-0000000000" + username.length())
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
    }
}
