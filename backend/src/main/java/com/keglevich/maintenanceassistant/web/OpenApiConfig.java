package com.keglevich.maintenanceassistant.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description: spec at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui}.
 *
 * <p>Both are readable without a token, because an API documentation nobody can reach documents
 * nothing — reachable API docs were explicit recruiter feedback. The described endpoints stay
 * protected either way.
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
        .components(new Components().addSecuritySchemes("keycloak", keycloak));
  }
}
