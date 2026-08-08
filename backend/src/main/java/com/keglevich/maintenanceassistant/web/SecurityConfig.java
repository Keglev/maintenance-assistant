package com.keglevich.maintenanceassistant.web;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
// Turns on @PreAuthorize. The filter chain below only separates "authenticated" from "public";
// which role may upload a protocol or run a backlog is stated on the method that does it, next to
// the code it guards, rather than as a path pattern that drifts away from the handler.
@EnableMethodSecurity
class SecurityConfig {

  /**
   * Readable without a token: the liveness probe and the API documentation.
   *
   * <p>The documentation is public by decision. A reviewer must be able to read what this API
   * offers without first obtaining a demo login, the endpoints are read-only, and the specification
   * describes shapes rather than data — the corpus behind it is synthetic in any case.
   */
  private static final String[] PUBLIC_PATHS = {
    "/api/health", "/actuator/health", "/actuator/health/**",
    "/v3/api-docs", "/v3/api-docs/**",
    // All three spellings of the entry point. `/swagger-ui/**` does not cover the bare path, and
    // a trailing slash is a fourth string as far as the matcher is concerned — a reader who types
    // any of them should not be told they lack permission.
    "/swagger-ui", "/swagger-ui/", "/swagger-ui.html", "/swagger-ui/**"
  };

  /**
   * The methods that make a path "readable".
   *
   * <p>GET alone was not enough, and the gap was invisible from a browser: {@code curl -I} sends
   * HEAD, HEAD is a separate method to the matcher, and every public path — the API documentation
   * and {@code /api/health} with it — answered 401 to it while answering a browser perfectly. Any
   * uptime probe using HEAD would have seen a permanently unauthorised health endpoint. Both
   * methods are safe and read-only, which is the property that makes these paths public at all.
   */
  private static final HttpMethod[] READ_METHODS = {HttpMethod.GET, HttpMethod.HEAD};

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter)
      throws Exception {
    return http
        // No cookies, no sessions: every request carries its own bearer token, so there is no
        // session for a cross-site request to ride on and CSRF protection buys nothing.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> {
          // An error dispatch is not a request a caller made; it is the container reporting what
          // became of one. Guarding it rewrote every 404 on a PERMITTED path into a 401, which is
          // how a missing mapping kept reading as a permission problem. Measured, in a real
          // container: /swagger-ui/ answered 401 with this line absent and 404 with it present.
          // Nothing is exposed — the error body carries neither message nor stack trace
          // (spring.web.error.*) — and a path that is not permitted is still refused before
          // dispatch, so this widens nothing. MockMvc cannot see any of it: it performs no error
          // dispatch, which is why the suite missed this twice.
          auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
          for (HttpMethod method : READ_METHODS) {
            auth.requestMatchers(method, PUBLIC_PATHS).permitAll();
          }
          // Everything else, including a path no handler serves, answers 401 rather than 404.
          // That is authorization running before dispatch, and it is the behaviour to keep: an
          // anonymous caller learns nothing about which paths exist.
          auth.anyRequest().authenticated();
        })
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
