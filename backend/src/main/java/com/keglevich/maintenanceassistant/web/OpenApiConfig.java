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
            // Markdown, which Swagger UI renders in place. The back-link is the only navigation
            // out of the documentation: the UI is a page of its own with no header of ours, so a
            // reader who arrives here from the README would otherwise have to reach for the
            // browser's back button to find the application it describes.
            .description("""
                Search industrial maintenance protocols in natural language (DE/EN) and get \
                answers with citations. Roles come from Keycloak; answers are filtered by role \
                server-side.

                [← Back to the application](https://maintenance.smartsupply.com.de/)""")
            .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
        .components(new Components().addSecuritySchemes("keycloak", keycloak));
  }
}
