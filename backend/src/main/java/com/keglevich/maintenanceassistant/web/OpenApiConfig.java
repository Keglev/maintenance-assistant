package com.keglevich.maintenanceassistant.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description: spec at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui}.
 *
 * <p>Both are readable without a token, because an API documentation nobody can reach documents
 * nothing — reachable API docs were explicit recruiter feedback. The described endpoints stay
 * protected either way.
 *
 * <p>THE BEARER REQUIREMENT IS DECLARED GLOBALLY (Part 5 ruling, K3.1, 2026-08-28). Every
 * operation is protected by {@code @PreAuthorize} except the health probe, and until this bean
 * declared it the published document said the opposite: sixteen of seventeen protected operations
 * appeared unauthenticated, and Swagger UI would not attach a token from Authorize. A contract
 * that understates an enforced constraint is worse than one that omits it, because a reader who
 * believes it writes a client that fails on the first call. {@link HealthController} clears the
 * requirement for its own operation, and it is the only one that may.
 */
@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI maintenanceAssistantOpenApi(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {

    SecurityScheme keycloak = new SecurityScheme()
        .type(SecurityScheme.Type.HTTP)
        .scheme("bearer")
        .bearerFormat("JWT")
        .description("Keycloak access token for the maintenance realm. Issuer: " + issuerUri);

    return new OpenAPI()
        .info(new Info()
            .title("maintenance-assistant API")
            // "v1" IS THE CONTRACT GENERATION, NOT THE RELEASE (Part 5 ruling, K3.5, 2026-08-28).
            // The paths are unversioned, so this number moves only when the contract breaks — not
            // when the application ships. The APPLICATION version is BuildProperties, reported by
            // GET /api/health and read from the build-info goal. Binding this field to it was
            // considered and rejected: it would move the contract generation on every release that
            // does not change the contract, which is the opposite of what a generation means.
            .version("v1")
            // Markdown, which Swagger UI renders in place. The two back-links are the only
            // navigation out of the documentation: the UI is a page of its own with no header of
            // ours, so a reader who arrives here — from the README or from the card on the docs
            // landing page — would otherwise have to reach for the browser's back button to find
            // either the application it describes or the documentation that sent them here.
            //
            // The spec's own description carries them on purpose. springdoc offers no property
            // for a link in the Swagger UI topbar, and the alternatives (a layout override, or a
            // static wrapper page around the distribution) mean shipping and maintaining assets
            // for one anchor. This is data in the document we already publish: no asset, no
            // inline script or style, and therefore nothing the Caddy CSP can refuse.
            //
            // FAILURE MODE, stated rather than assumed: if a future Swagger UI stops rendering
            // markdown in the description, these degrade to visible literal text — wrong-looking,
            // not invisible. OpenApiSpecIT asserts both URLs are in the served document, which
            // catches the configuration being lost; it cannot catch a rendering change, and only
            // opening the page does.
            .description("""
                Search industrial maintenance protocols in natural language (DE/EN) and get \
                answers with citations. Roles come from Keycloak; answers are filtered by role \
                server-side.

                [← Back to the application](https://maintenance.smartsupply.com.de/) · \
                [Documentation site](https://keglev.github.io/maintenance-assistant/)""")
            .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
        .components(new Components().addSecuritySchemes("keycloak", keycloak))
        // Applied to every operation that does not clear it. Declared here rather than annotated on
        // each controller because the default is the safe one: a new endpoint inherits the
        // requirement by existing, and opting out is the act that has to be written down.
        .security(List.of(new SecurityRequirement().addList("keycloak")));
  }
}
