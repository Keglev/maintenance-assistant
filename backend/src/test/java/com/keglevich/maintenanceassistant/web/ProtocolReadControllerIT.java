package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read a source document, and what happens when the file behind a citation is gone.
 *
 * <p>The services are stubbed: what is under test here is the authorisation rule and the HTTP
 * shape, which is all the web layer decides. That the file actually comes off the volume is covered
 * against a real database and a real temp directory in {@code ProtocolDocumentIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProtocolReadControllerIT {

    private static final UUID PROTOCOL = UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    /** The production converter, so the test asserts the role mapping the application really runs. */
    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private ProtocolDocumentService documents;

    @MockitoBean
    private ProtocolStatusService statuses;

    // ---------------------------------------------------------------------------------------
    // The source document
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a document is rejected without a token")
    void documentRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/protocols/{id}/document", PROTOCOL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an operator may open the source behind a citation")
    void anOperatorMayReadASource() throws Exception {
        // The role that gets the most restricted *answers* still gets the sources behind them:
        // Mode A's promise is that a claim can be checked, and a citation nobody may follow is a
        // citation they have to take on trust.
        when(documents.find(PROTOCOL)).thenReturn(Optional.of(document()));

        mockMvc.perform(read("operator", "operator"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Symptom")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("PR-03-E-47-Druckabfall.txt")));
    }

    @Test
    @DisplayName("a techniker may too")
    void aTechnikerMayReadASource() throws Exception {
        when(documents.find(PROTOCOL)).thenReturn(Optional.of(document()));

        mockMvc.perform(read("techniker", "techniker")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("admin may not — it is an IT role with no shop-floor business")
    void adminMayNotReadASource() throws Exception {
        mockMvc.perform(read("admin", "admin")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a protocol whose file is missing is a 404, indistinguishable from an unknown id")
    void aMissingFileIsNotFound() throws Exception {
        // Both cases arrive here as an empty Optional on purpose. Telling them apart in the
        // response would describe the database to whoever asked, and the caller can do nothing
        // differently either way.
        when(documents.find(any())).thenReturn(Optional.empty());

        mockMvc.perform(read("techniker", "techniker")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------
    // Own uploads
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a schichtleiter sees their own uploads with status and failure reason")
    void ownUploadsAreListedForTheUploader() throws Exception {
        when(statuses.findRecentUploadsOf("schichtleiter")).thenReturn(List.of(
                new ProtocolStatusService.UploadStatus(PROTOCOL, "PR-03", "E-47 Druckabfall",
                        "INDEXED", null, OffsetDateTime.now(), OffsetDateTime.now()),
                new ProtocolStatusService.UploadStatus(UUID.randomUUID(), "FB-04", "Bandschaden",
                        "FAILED", "IllegalStateException: source file is not UTF-8 text",
                        OffsetDateTime.now(), null)));

        mockMvc.perform(get("/api/protocols/mine")
                        .with(authentication(jwtAuthenticationConverter.convert(
                                keycloakToken("schichtleiter", "schichtleiter")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("INDEXED"))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].failureReason")
                        .value(org.hamcrest.Matchers.containsString("not UTF-8")));
    }

    /*
     * SELF-SCOPING, PARAMETERISED OVER BOTH WRITERS rather than copied for the second one.
     *
     * Decision 3 gave the Techniker the upload and, on 2026-08-28, this list. The rule that makes
     * the second role safe is that the query takes the caller's own preferred_username, so the
     * seeded corpus holds one upload per writer and each caller must see only their own.
     */
    @ParameterizedTest(name = "a {0} sees only their own uploads")
    @ValueSource(strings = {"techniker", "schichtleiter"})
    @DisplayName("each writer's upload list is scoped to their own uploads")
    void ownUploadsAreScopedToTheCaller(String role) throws Exception {
        when(statuses.findRecentUploadsOf("techniker")).thenReturn(List.of(upload("PR-03")));
        when(statuses.findRecentUploadsOf("schichtleiter")).thenReturn(List.of(upload("FB-04")));
        String theirs = "techniker".equals(role) ? "PR-03" : "FB-04";

        mockMvc.perform(get("/api/protocols/mine")
                        .with(authentication(jwtAuthenticationConverter.convert(
                                keycloakToken(role, role)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].machineNo").value(theirs));
    }

    @Test
    @DisplayName("an operator has no upload list, because they may not write")
    void ownUploadsAreRefusedToNonWriters() throws Exception {
        mockMvc.perform(get("/api/protocols/mine")
                        .with(authentication(jwtAuthenticationConverter.convert(
                                keycloakToken("operator", "operator")))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    /** One INDEXED upload on the given machine, which is all the scoping assertions read. */
    private static ProtocolStatusService.UploadStatus upload(String machineNo) {
        return new ProtocolStatusService.UploadStatus(UUID.randomUUID(), machineNo,
                "E-47 Druckabfall", "INDEXED", null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private MockHttpServletRequestBuilder read(String username, String... realmRoles) {
        return get("/api/protocols/{id}/document", PROTOCOL)
                .with(authentication(jwtAuthenticationConverter.convert(
                        keycloakToken(username, realmRoles))));
    }

    private static ProtocolDocumentService.ProtocolDocument document() {
        byte[] bytes = "Symptom:\nPresse kommt nicht auf Druck.\n".getBytes(StandardCharsets.UTF_8);
        return new ProtocolDocumentService.ProtocolDocument(
                new ByteArrayResource(bytes), "PR-03-E-47-Druckabfall.txt",
                "text/plain;charset=UTF-8", bytes.length);
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
