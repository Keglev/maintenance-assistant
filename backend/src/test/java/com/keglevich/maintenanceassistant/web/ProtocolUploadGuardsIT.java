package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the upload endpoint refuses, and the guarantee that a refusal stores nothing.
 *
 * <p>The intake service is stubbed on purpose: the subject here is the gate in front of it. Every
 * assertion that a rejection stored nothing is made by verifying the service was <b>never called</b>
 * — which is the whole nothing-stored guarantee, since the row, the file and the indexing event are
 * all on the far side of that one call.
 *
 * <p>The container's own size limit is NOT tested here and cannot be: MockMvc does not run Tomcat's
 * multipart parser, so an oversized part never gets refused in this harness. That case lives in
 * {@code UploadSizeLimitIT} against a real container — the same lesson the HEAD defect taught in
 * PROJECT-PHASES.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// A small, explicit limit so the burst case is cheap to provoke and the assertion reads against a
// number this file states rather than against whatever the production default happens to be.
@TestPropertySource(properties = "maintenance.ingestion.uploads-per-minute=3")
class ProtocolUploadGuardsIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private ProtocolIntakeService intake;

    /**
     * A username per test method.
     *
     * <p>The limiter is an application singleton and its buckets deliberately outlive a request —
     * that is what a rate limiter is. So tests sharing one username share one bucket, and whichever
     * ran first would spend it for the rest. Keying on the test's own name isolates them and states
     * the limiter's contract at the same time: the bucket belongs to the user, not to the request.
     */
    private String user;

    @BeforeEach
    void nameTheUser(TestInfo testInfo) {
        user = testInfo.getTestMethod().orElseThrow().getName();
    }

    @Test
    @DisplayName("a normal .txt is accepted with 202 and reaches the intake service")
    void aNormalTextFileIsAccepted() throws Exception {
        when(intake.accept(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(upload("protokoll.txt", "Symptom:\nPresse kommt nicht auf Druck.\n"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        verify(intake).accept(any());
    }

    @Test
    @DisplayName("an empty file is refused, and nothing is stored")
    void anEmptyFileIsRefused() throws Exception {
        mockMvc.perform(upload("protokoll.txt", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("EMPTY_FILE"));

        verify(intake, never()).accept(any());
    }

    @Test
    @DisplayName("a file with NUL bytes is refused as not text, and nothing is stored")
    void aBinaryFileIsRefused() throws Exception {
        // A PDF header, which is what this actually guards against: it would otherwise reach the
        // strict UTF-8 decoder and be reported as a malformed byte sequence rather than as the
        // wrong kind of file.
        byte[] binary = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', 0, 0, 1, 2, 3};

        mockMvc.perform(upload(new MockMultipartFile("file", "scan.txt", "text/plain", binary)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NOT_TEXT"));

        verify(intake, never()).accept(any());
    }

    @Test
    @DisplayName("a .md file is refused now that protocols can be typed in the app")
    void markdownIsNoLongerAccepted() throws Exception {
        // Markdown was a developer convenience from when writing a file was the only way in. Typed
        // entry replaced that, so the extension no longer has to accommodate it (DECISIONS.txt).
        mockMvc.perform(upload("notizen.md", "# Protokoll\n\nKein Druck.\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_TYPE"));

        verify(intake, never()).accept(any());
    }

    @Test
    @DisplayName("a file with no extension is refused — it is a renamed binary until proven otherwise")
    void anExtensionlessFileIsRefused() throws Exception {
        mockMvc.perform(upload("protokoll", "Kein Druck."))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNSUPPORTED_TYPE"));

        verify(intake, never()).accept(any());
    }

    @Test
    @DisplayName("the fourth upload in a minute is a 429 carrying Retry-After")
    void aBurstIsRateLimited() throws Exception {
        when(intake.accept(any())).thenReturn(UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(upload("protokoll.txt", "Protokoll " + i))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(upload("protokoll.txt", "Eins zu viel"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.reason").value("RATE_LIMITED"));

        // Three accepted, and the refused one never reached the service: a limit that still stored
        // the protocol would be a log line, not a limit.
        verify(intake, org.mockito.Mockito.times(3)).accept(any());
    }

    @Test
    @DisplayName("the limit is per user, so one busy Schichtleiter does not throttle another")
    void theLimitIsPerUser() throws Exception {
        when(intake.accept(any())).thenReturn(UUID.randomUUID());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(upload(user, "protokoll.txt", "Protokoll " + i))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(upload(user, "protokoll.txt", "Eins zu viel"))
                .andExpect(status().isTooManyRequests());

        // A different username, a different bucket. Keyed on preferred_username because that is the
        // identity the resulting rows are attributed to.
        mockMvc.perform(upload(user + "-nachtschicht", "protokoll.txt", "Erstes der Nachtschicht"))
                .andExpect(status().isAccepted());
    }

    // ---------------------------------------------------------------------------------------

    private MockMultipartHttpServletRequestBuilder upload(String filename, String content) {
        return upload(user, filename, content);
    }

    private MockMultipartHttpServletRequestBuilder upload(String username, String filename,
                                                          String content) {
        return upload(username, new MockMultipartFile(
                "file", filename, "text/plain", content.getBytes(StandardCharsets.UTF_8)));
    }

    private MockMultipartHttpServletRequestBuilder upload(MockMultipartFile file) {
        return upload(user, file);
    }

    private MockMultipartHttpServletRequestBuilder upload(String username, MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder builder =
                (MockMultipartHttpServletRequestBuilder) multipart("/api/protocols")
                        .file(file)
                        .param("machine", "PR-03")
                        .param("type", "STOERUNG")
                        .param("title", "E-47 Druckabfall")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .with(authentication(jwtAuthenticationConverter.convert(
                                keycloakToken(username, "schichtleiter"))));
        return builder;
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
