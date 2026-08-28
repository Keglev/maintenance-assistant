package com.keglevich.maintenanceassistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated probe endpoint for the walking skeleton.
 *
 * <p>It exists to prove the whole authentication path end to end: Keycloak issued the token, the
 * resource server validated its signature and audience, and the realm roles arrived as
 * authorities. Phase 1 is done when this answers on the VPS after a real login.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "System", description = "Public service information")
class HelloController {

  @GetMapping("/hello")
  @Operation(
      summary = "Identity as the backend sees it",
      // The bearer requirement is not repeated here: OpenApiConfig declares it globally, and this
      // operation inherits it like every other. Asserted from the served document by OpenApiSpecIT.
      description = "Requires a valid Keycloak access token for the maintenance realm.")
  HelloResponse hello(Authentication authentication) {
    // Spring Security 7 also grants authentication-factor authorities such as FACTOR_BEARER.
    // Those describe *how* the caller authenticated, not what they are allowed to do, so only
    // the role authorities mapped from realm_access are reported here.
    List<String> roles = authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(authority -> authority.startsWith("ROLE_"))
        .sorted()
        .toList();

    return new HelloResponse(authentication.getName(), roles);
  }

  /**
   * @param username the {@code preferred_username} claim, as configured on the principal
   * @param roles realm roles mapped to authorities, for example {@code ROLE_TECHNIKER}
   */
  record HelloResponse(String username, List<String> roles) {}
}
