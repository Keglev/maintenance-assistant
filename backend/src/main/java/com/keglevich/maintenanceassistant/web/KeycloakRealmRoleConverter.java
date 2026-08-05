package com.keglevich.maintenanceassistant.web;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak realm roles onto Spring Security authorities.
 *
 * <p>Keycloak delivers realm roles in the access token as
 * <pre>{"realm_access": {"roles": ["techniker", "default-roles-maintenance"]}}</pre>
 * while Spring Security's role-based checks ({@code hasRole}, {@code @PreAuthorize("hasRole(…)")})
 * expect authorities prefixed with {@code ROLE_}. This converter bridges the two, so
 * {@code techniker} becomes {@code ROLE_TECHNIKER}.
 *
 * <p>Roles are not filtered here: the token may legitimately carry Keycloak's own built-ins
 * (for example {@code offline_access}). Authorization asks for the roles it needs, rather than
 * this converter guessing which ones matter.
 */
class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

  private static final String REALM_ACCESS_CLAIM = "realm_access";
  private static final String ROLES_KEY = "roles";
  private static final String AUTHORITY_PREFIX = "ROLE_";

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
    if (realmAccess == null) {
      // A token without realm_access is valid — it simply carries no realm role.
      return List.of();
    }

    Object roles = realmAccess.get(ROLES_KEY);
    if (!(roles instanceof Collection<?> roleValues)) {
      return List.of();
    }

    return roleValues.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .filter(role -> !role.isBlank())
        .map(role -> AUTHORITY_PREFIX + role.toUpperCase(Locale.ROOT))
        .distinct()
        .<GrantedAuthority>map(SimpleGrantedAuthority::new)
        .toList();
  }
}
