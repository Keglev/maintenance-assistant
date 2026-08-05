package com.keglevich.maintenanceassistant.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration: the application is an OAuth2 Resource Server (ADR-003).
 *
 * <p>It validates Keycloak-issued JWTs offline against the realm's JWKS — configured through
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} — and never issues tokens itself.
 * Realm roles from the token become {@code ROLE_*} authorities via
 * {@link KeycloakRealmRoleConverter}, so the four roles (operator, techniker, schichtleiter,
 * admin) are enforceable server-side.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

  /** Readable without a token: the liveness probe and the API documentation. */
  private static final String[] PUBLIC_PATHS = {
    "/api/health", "/actuator/health", "/actuator/health/**",
    "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
  };

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter)
      throws Exception {
    return http
        // No cookies, no sessions: every request carries its own bearer token, so there is no
        // session for a cross-site request to ride on and CSRF protection buys nothing.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
            .anyRequest().authenticated())
        // Wired explicitly: a custom SecurityFilterChain opts out of Boot's auto-configuration,
        // so the converter bean would otherwise be ignored and roles would never arrive.
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }

  /**
   * Turns a validated JWT into an {@code Authentication}: realm roles become authorities and the
   * principal name is the Keycloak username rather than the opaque subject id, which is what
   * {@code uploaded_by} stores for traceability.
   */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    converter.setPrincipalClaimName("preferred_username");
    return converter;
  }
}
