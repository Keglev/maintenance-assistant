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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may read the machine list.
 *
 * <p>It gained a fourth role, and the reason is a defect that reached production: the admin's
 * landing page called this endpoint, the endpoint correctly refused a role with no shop-floor
 * function, and the first thing an administrator saw after signing in was "Maschinenliste nicht
 * verfügbar". The fix is not to soften the rule everywhere — an admin still may not ask a question —
 * but to say that plant metadata is readable by the role that now moderates the corpus and filters
 * it by machine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MachineSecurityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private MachineCatalog machines;

    @BeforeEach
    void plant() {
        when(machines.findAll()).thenReturn(List.of(new MachineCatalog.Machine(
                UUID.fromString("0f9c5b02-0000-4000-8000-0000000000aa"),
                "PR-03", "Presse 3", "Presse", "Halle 1")));
    }

    @ParameterizedTest(name = "a {0} reads the machine list")
    @ValueSource(strings = {"operator", "techniker", "schichtleiter"})
    void everyShopFloorRoleMayList(String role) throws Exception {
        mockMvc.perform(get("/api/machines").with(as(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].machineNo").value("PR-03"));
    }

    @Test
    @DisplayName("an admin reads the machine list, because the moderation filter needs it")
    void anAdminMayList() throws Exception {
        // Without this the moderation view can offer no machine dropdown, and the machine-first
        // filter rule would be a rule the interface cannot help anyone follow.
        mockMvc.perform(get("/api/machines").with(as("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].machineNo").value("PR-03"));
    }

    @Test
    @DisplayName("the machine list still requires a token")
    void listingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/machines")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a signed-in user holding no realm role is still refused")
    void aRolelessUserIsRefused() throws Exception {
        // Widening to admin widened it by one named role, not to "anyone with a token".
        mockMvc.perform(get("/api/machines")
                        .with(authentication(jwtAuthenticationConverter.convert(
                                keycloakToken("nobody")))))
                .andExpect(status().isForbidden());
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
