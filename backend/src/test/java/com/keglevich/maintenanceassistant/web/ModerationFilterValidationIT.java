package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolEditService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * How the corpus list accepts a filter, at the HTTP boundary.
 *
 * <p>The rule under test is the machine-first one: a title fragment or a date range without a
 * machine answers with rows from machines the reviewer was not looking at, which is noise wearing
 * the clothes of a result. It is refused rather than quietly widened, and it is refused with a
 * <b>stable code</b> — the frontend matches on the code, so rewording the sentence here cannot
 * change what the German interface says.
 *
 * <p>What the query does with a valid filter is {@code ProtocolModerationIT}'s business; this suite
 * only proves that what the caller typed reaches the service intact.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModerationFilterValidationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private static final java.util.UUID PROTOCOL =
            java.util.UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");

    @MockitoBean
    private ProtocolModerationService moderation;

    @MockitoBean
    private ProtocolEditService edits;

    @MockitoBean
    private ProtocolDocumentService documents;

    @ParameterizedTest(name = "{0}={1} without a machine is refused")
    @CsvSource({
            "titleContains, sensor",
            "from, 2026-08-01",
            "to, 2026-08-31"
    })
    void aFilterWithoutAMachineIsRefused(String param, String value) throws Exception {
        mockMvc.perform(get("/api/moderation/protocols").param(param, value).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("MACHINE_REQUIRED_FOR_FILTER"));

        // Refused before the query, not after: a 400 that had already scanned the corpus would be a
        // status code rather than a rule.
        verify(moderation, never()).list(anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("no filter at all is still the plain list")
    void noFilterIsTheUnfilteredList() throws Exception {
        when(moderation.list(anyInt(), anyInt(), any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/moderation/protocols").with(admin()))
                .andExpect(status().isOk());

        ProtocolModerationService.ProtocolFilter filter = capturedFilter();
        assertThat(filter.machineNo()).isNull();
        assertThat(filter.titleContains()).isNull();
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
    }

    @Test
    @DisplayName("an empty parameter is an unfilled field, not a search for the empty string")
    void blankParametersAreNotAFilter() throws Exception {
        when(moderation.list(anyInt(), anyInt(), any())).thenReturn(emptyPage());

        // A form that submits every field sends the untouched ones as empty. Treating that as a
        // filter would make "clear the title box" a 400 for the machine-first rule.
        mockMvc.perform(get("/api/moderation/protocols")
                        .param("machineNo", "").param("titleContains", "").with(admin()))
                .andExpect(status().isOk());

        assertThat(capturedFilter().machineNo()).isNull();
    }

    @Test
    @DisplayName("machine, title and both dates arrive at the service as typed")
    void aFullFilterReachesTheService() throws Exception {
        when(moderation.list(anyInt(), anyInt(), any())).thenReturn(emptyPage());

        mockMvc.perform(get("/api/moderation/protocols")
                        .param("machineNo", "PR-03")
                        .param("titleContains", "Sensor")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .with(admin()))
                .andExpect(status().isOk());

        ProtocolModerationService.ProtocolFilter filter = capturedFilter();
        assertThat(filter.machineNo()).isEqualTo("PR-03");
        assertThat(filter.titleContains()).isEqualTo("Sensor");
        assertThat(filter.from()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(filter.to()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @ParameterizedTest(name = "a machine with only {0} is accepted")
    @CsvSource({"from, 2026-08-01", "to, 2026-08-31"})
    void anOpenEndedRangeIsAllowed(String param, String value) throws Exception {
        when(moderation.list(anyInt(), anyInt(), any())).thenReturn(emptyPage());

        // "Everything since the first" and "everything up to the last" are both questions a
        // reviewer asks; requiring the other end would make them ask a different one.
        mockMvc.perform(get("/api/moderation/protocols")
                        .param("machineNo", "PR-03").param(param, value).with(admin()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------
    // How a refused moderation act reaches the client
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a moderation act without a comment is a 400 with a stable code")
    void aMissingCommentIsABadRequest() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenThrow(
                new ProtocolModerationService.InvalidModerationRequestException(
                        ProtocolModerationService.COMMENT_REQUIRED, "a moderation comment is required"));

        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"C\"}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("MODERATION_COMMENT_REQUIRED"));
    }

    @Test
    @DisplayName("changing machine or type is a 400 with the identity code, not a silent no-op")
    void movingAProtocolIsABadRequest() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenThrow(
                new ProtocolModerationService.InvalidModerationRequestException(
                        ProtocolEditService.IDENTITY_LOCKED, "machine cannot be changed by an edit"));

        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineNo\":\"AB-02\",\"title\":\"T\",\"content\":\"C\",\"comment\":\"x\"}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("PROTOCOL_IDENTITY_LOCKED"));
    }

    @Test
    @DisplayName("editing an archived protocol is a 409, not a 404")
    void editingAnArchivedProtocolIsAConflict() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenThrow(
                new ProtocolModerationService.InvalidModerationRequestException(
                        ProtocolEditService.PROTOCOL_ARCHIVED, "this protocol is in the archive"));

        // The protocol exists and the same administrator can read it in the archive one tab over.
        // "No such protocol" would be a lie their own screen contradicts: what is wrong is the
        // state, not the address.
        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"C\",\"comment\":\"x\"}")
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("PROTOCOL_ARCHIVED"));
    }

    @Test
    @DisplayName("an empty or oversized correction is a 400 from the same rules as an upload")
    void badContentIsABadRequest() throws Exception {
        when(edits.edit(any(), any(), anyString())).thenThrow(
                new ProtocolIntakeService.InvalidProtocolException("content is required"));

        mockMvc.perform(put("/api/moderation/protocols/{id}", PROTOCOL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"\",\"comment\":\"x\"}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_CONTENT"));
    }

    // ---------------------------------------------------------------------------------------

    private ProtocolModerationService.ProtocolFilter capturedFilter() {
        ArgumentCaptor<ProtocolModerationService.ProtocolFilter> captor =
                ArgumentCaptor.forClass(ProtocolModerationService.ProtocolFilter.class);
        verify(moderation).list(anyInt(), anyInt(), captor.capture());
        return captor.getValue();
    }

    private static ProtocolModerationService.ProtocolPage emptyPage() {
        return new ProtocolModerationService.ProtocolPage(List.of(), 0, 10, 0);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return authentication(jwtAuthenticationConverter.convert(keycloakToken()));
    }

    private static Jwt keycloakToken() {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("iss", "http://localhost:8081/realms/maintenance")
                .claim("aud", List.of("backend"))
                .claim("azp", "frontend")
                .claim("sub", "00000000-0000-0000-0000-000000000005")
                .claim("preferred_username", "admin")
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .build();
    }
}
