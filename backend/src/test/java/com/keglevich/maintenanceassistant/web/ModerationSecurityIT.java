package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        mockMvc.perform(delete("/api/moderation/protocols/{id}", PROTOCOL).with(as(role)))
                .andExpect(status().isForbidden());

        // Refused before anything happened, not after: a 403 that had already deleted the row
        // would be a status code, not a permission check.
        verify(moderation, never()).delete(any(), anyString());
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
        when(moderation.list(anyInt(), anyInt())).thenReturn(new ProtocolModerationService.ProtocolPage(
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
    @DisplayName("an admin deletes a protocol and gets 204")
    void anAdminMayDelete() throws Exception {
        when(moderation.delete(any(), anyString())).thenReturn(true);

        mockMvc.perform(delete("/api/moderation/protocols/{id}", PROTOCOL).with(as("admin")))
                .andExpect(status().isNoContent());

        // The username, not the subject: the deletion log and the authorship it judges are written
        // in the same identity.
        verify(moderation).delete(PROTOCOL, "admin");
    }

    @Test
    @DisplayName("deleting an unknown protocol is a 404")
    void deletingWhatIsNotThereIsNotFound() throws Exception {
        when(moderation.delete(any(), anyString())).thenReturn(false);

        mockMvc.perform(delete("/api/moderation/protocols/{id}", UUID.randomUUID()).with(as("admin")))
                .andExpect(status().isNotFound());
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
