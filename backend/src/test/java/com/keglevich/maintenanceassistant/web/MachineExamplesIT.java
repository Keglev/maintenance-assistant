package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.query.MachineCatalog;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The example-question endpoint: its shape, its 404, and who may read it.
 *
 * <p>The real resource file is on the classpath and is NOT stubbed — that is the point of the shape
 * assertions below. A test that mocked the examples away would pass over a file that failed to
 * package, which is exactly the failure the startup validation exists to catch.
 *
 * <p>The machine catalogue IS mocked, because this test is about the web layer: which role gets
 * through, what a missing machine produces, and whether the payload carries what a client needs.
 * Whether the count itself is right is {@code MachineCatalog}'s question and is answered against a
 * real database.
 *
 * <p>SIBLING: MachineSecurityIT, which owns the same questions for the machine list.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MachineExamplesIT {

    private static final UUID PR03 = UUID.fromString("0f9c5b02-0000-4000-8000-0000000000aa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private MachineCatalog machines;

    @BeforeEach
    void plant() {
        when(machines.findByMachineNo("PR-03")).thenReturn(Optional.of(
                new MachineCatalog.Machine(PR03, "PR-03", "Presse 3", "Presse", "Halle 1")));
        when(machines.findByMachineNo("XX-99")).thenReturn(Optional.empty());
        when(machines.countLiveProtocols(any())).thenReturn(24);
    }

    @Test
    @DisplayName("a known machine returns its questions in both languages, with the protocol count")
    void aKnownMachineReturnsItsExamples() throws Exception {
        mockMvc.perform(get("/api/machines/PR-03/examples").with(as("techniker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machineNo").value("PR-03"))
                .andExpect(jsonPath("$.protocolCount").value(24))
                // Three to four per language is the ruled shape; the file is real, so this is also
                // a check that it survived packaging.
                .andExpect(jsonPath("$.examples.de.length()").value(4))
                .andExpect(jsonPath("$.examples.en.length()").value(4))
                .andExpect(jsonPath("$.examples.de[0].question").isNotEmpty())
                // The source id travels with the question. It is not shown to anyone; it is what
                // lets ExampleQuestionsTest fail when that protocol leaves the corpus.
                .andExpect(jsonPath("$.examples.de[0].source").isNotEmpty());
    }

    @Test
    @DisplayName("the E-47 demo question is one of them, because the walkthrough depends on it")
    void theDemoQuestionIsOffered() throws Exception {
        // Pinned rather than left to the file: the ≤90-second recruiter walkthrough opens on this
        // question, and an edit that dropped it would break the demo silently.
        mockMvc.perform(get("/api/machines/PR-03/examples").with(as("techniker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examples.de[*].question",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("E-47"))));
    }

    @ParameterizedTest(name = "a {0} may read the examples")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter", "admin"})
    void everyRoleMayRead(String role) throws Exception {
        // The OPERATOR case is the one with a decision behind it: the examples are QUESTIONS, and
        // what an operator may be TOLD is filtered on the answer path (ADR-006). Filtering the
        // questions as well would mean a second role matrix over content carrying no protocol text.
        mockMvc.perform(get("/api/machines/PR-03/examples").with(as(role)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unknown machine is 404, not an empty list")
    void anUnknownMachineIs404() throws Exception {
        // An empty list means "this machine has no examples", which is a real and different answer.
        // Conflating the two would tell a client with a typo that the plant has nothing to say.
        mockMvc.perform(get("/api/machines/XX-99/examples").with(as("techniker")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a machine that exists with no examples returns empty lists and still answers 200")
    void aMachineWithoutExamplesIsNotAnError() throws Exception {
        when(machines.findByMachineNo("ZZ-01")).thenReturn(Optional.of(
                new MachineCatalog.Machine(UUID.randomUUID(), "ZZ-01", "Neu", "Presse", "Halle 9")));
        when(machines.countLiveProtocols(any())).thenReturn(0);

        mockMvc.perform(get("/api/machines/ZZ-01/examples").with(as("techniker")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocolCount").value(0))
                .andExpect(jsonPath("$.examples.de.length()").value(0))
                .andExpect(jsonPath("$.examples.en.length()").value(0));
    }

    @Test
    @DisplayName("the endpoint requires a token")
    void itRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/machines/PR-03/examples"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a signed-in user holding no realm role is refused")
    void aRolelessUserIsRefused() throws Exception {
        mockMvc.perform(get("/api/machines/PR-03/examples")
                        .with(authentication(jwtAuthenticationConverter.convert(keycloakToken("nobody")))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------

    private RequestPostProcessor as(String role) {
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
