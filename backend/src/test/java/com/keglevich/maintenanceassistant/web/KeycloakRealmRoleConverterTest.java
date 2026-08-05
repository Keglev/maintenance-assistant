package com.keglevich.maintenanceassistant.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

  private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

  @Test
  @DisplayName("realm roles become uppercase ROLE_ authorities")
  void mapsRealmRoles() {
    Jwt token = tokenWithRealmAccess(Map.of("roles", List.of("techniker", "offline_access")));

    assertThat(authorityNames(token))
        .containsExactlyInAnyOrder("ROLE_TECHNIKER", "ROLE_OFFLINE_ACCESS");
  }

  @Test
  @DisplayName("a token without realm_access yields no authorities instead of failing")
  void toleratesMissingClaim() {
    Jwt token = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "anyone").build();

    assertThat(converter.convert(token)).isEmpty();
  }

  @Test
  @DisplayName("a malformed realm_access claim yields no authorities")
  void toleratesMalformedClaim() {
    Jwt token = tokenWithRealmAccess(Map.of("roles", "techniker"));

    assertThat(converter.convert(token)).isEmpty();
  }

  @Test
  @DisplayName("blank and duplicate roles are dropped")
  void ignoresBlankAndDuplicateRoles() {
    Jwt token = tokenWithRealmAccess(Map.of("roles", List.of("admin", "admin", "  ")));

    assertThat(authorityNames(token)).containsExactly("ROLE_ADMIN");
  }

  private List<String> authorityNames(Jwt token) {
    return converter.convert(token).stream().map(GrantedAuthority::getAuthority).toList();
  }

  private static Jwt tokenWithRealmAccess(Map<String, Object> realmAccess) {
    return Jwt.withTokenValue("t")
        .header("alg", "RS256")
        .claim("realm_access", realmAccess)
        .build();
  }
}
