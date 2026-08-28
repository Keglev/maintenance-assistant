package com.keglevich.maintenanceassistant.web;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The walking skeleton's proof that the whole authentication path works end to end.
 *
 * <p>What it asserts is the CHAIN, not the handler: Keycloak issued the token, the resource server
 * validated it, and the realm roles arrived as authorities under the names the application
 * authorises on. That is why the identity cases assert the ROLES in the body rather than a status
 * — a 200 alone would pass with the role mapping broken, which is the one thing this endpoint
 * exists to demonstrate.
 *
 * <p>OUT OF SCOPE: which roles may reach which endpoint, covered exhaustively by
 * {@link RoleMatrixIT}; and the converter's own edge cases, covered by
 * {@link KeycloakRealmRoleConverterTest}. This is the integration of the two.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HelloControllerIT {

  @Autowired private MockMvc mockMvc;

  /**
   * The production converter bean, so the test asserts the configuration the application really
   * runs. The {@code jwt()} post-processor from spring-security-test is deliberately not used: it
   * builds its own authentication and would silently bypass this mapping.
   */
  @Autowired private JwtAuthenticationConverter jwtAuthenticationConverter;

  @Test
  @DisplayName("GET /api/hello is rejected without a token")
  void helloRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/hello")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("GET /api/hello returns username and realm roles for a techniker token")
  void helloReturnsIdentityForTechniker() throws Exception {
    Jwt token = keycloakToken("techniker", "techniker");

    mockMvc
        .perform(get("/api/hello").with(authentication(jwtAuthenticationConverter.convert(token))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("techniker"))
        .andExpect(jsonPath("$.roles", contains("ROLE_TECHNIKER")));
  }

  @Test
  @DisplayName("an operator token yields only the operator role")
  void helloReturnsIdentityForOperator() throws Exception {
    Jwt token = keycloakToken("operator", "operator");

    mockMvc
        .perform(get("/api/hello").with(authentication(jwtAuthenticationConverter.convert(token))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("operator"))
        .andExpect(jsonPath("$.roles", contains("ROLE_OPERATOR")));
  }

  /** A token shaped like the one Keycloak issues for the maintenance realm. */
  private static Jwt keycloakToken(String username, String... realmRoles) {
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .claim("iss", "http://localhost:8081/realms/maintenance")
        .claim("aud", List.of("backend"))
        .claim("azp", "frontend")
        .claim("sub", "00000000-0000-0000-0000-000000000000")
        .claim("preferred_username", username)
        .claim("realm_access", Map.of("roles", List.of(realmRoles)))
        .build();
  }
}
