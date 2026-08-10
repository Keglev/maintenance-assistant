package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolEditService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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
 * <p>The interesting assertion is not that an admin may — it is that a <b>Schichtleiter may not</b>.
 * They are the only role that can write to the corpus, which makes them the role ADR-006 is about;
 * a moderation endpoint they could reach would let the author of a bad protocol quietly remove the
 * evidence, and the audit function would be judging itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModerationSecurityIT {

    private static final UUID PROTOCOL = UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");

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

    // ---------------------------------------------------------------------------------------
    // Refused
    // ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "a {0} may not list the corpus")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMayList(String role) throws Exception {
        mockMvc.perform(get("/api/moderation/protocols").with(as(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "a {0} may not read a protocol through the moderation path")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMayReadThroughModeration(String role) throws Exception {
        // The shop-floor document endpoint stays open to them; this one is not it. Reading for
        // review is an admin act on an admin path.
        mockMvc.perform(get("/api/moderation/protocols/{id}/document", PROTOCOL).with(as(role)))
                .andExpect(status().isForbidden());
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
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void noShopFloorRoleMayEdit(String role) throws Exception {
        // The Schichtleiter matters most here, and for the same reason as the delete: they are the
        // only role that can write to the corpus, so a correction tool they could reach would let
        // the author of a bad protocol quietly rewrite it after someone started asking about it.
        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Neu\",\"content\":\"Text\",\"comment\":\"korrigiert\"}")
                        .with(as(role)))
                .andExpect(status().isForbidden());

        verify(edits, never()).edit(any(), any(), anyString());
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
                        "schichtleiter", OffsetDateTime.now(), "INDEXED", 2)),
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
    @DisplayName("an admin edits a protocol and gets 202 — indexing is asynchronous")
    void anAdminMayEdit() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenReturn(Optional.of(PROTOCOL));

        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"E-47 Druckabfall","errorCode":"E-47",
                                 "content":"Symptom:\\nKein Druck.\\n","comment":"Drehmoment korrigiert"}
                                """)
                        .with(as("admin")))
                // 202 rather than 200, matching upload: the protocol is corrected when this
                // returns and it is not searchable again until the re-index finishes.
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        verify(edits).edit(eq(PROTOCOL), any(), eq("admin"));
    }

    @Test
    @DisplayName("editing an unknown protocol is a 404")
    void editingWhatIsNotThereIsNotFound() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/moderation/protocols/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"C\",\"comment\":\"warum\"}")
                        .with(as("admin")))
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
