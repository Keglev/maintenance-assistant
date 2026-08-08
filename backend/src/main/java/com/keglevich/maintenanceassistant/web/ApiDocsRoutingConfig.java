package com.keglevich.maintenanceassistant.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sends the spellings of the documentation entry point that springdoc does not map to the one it
 * does.
 *
 * <p>springdoc registers exactly one path — the configured {@code springdoc.swagger-ui.path} — and
 * serves the UI's own assets from {@code /swagger-ui/**}. Everything adjacent to that is a 404: a
 * trailing slash is a different path to the matcher, and {@code /swagger-ui.html} is springdoc's
 * former default, which is what earlier versions of this project's README published and what any
 * bookmark from that time still points at.
 *
 * <p>Two redirects rather than a custom documentation page: nothing about Swagger UI's own HTML is
 * touched, and the reader ends up where they meant to go instead of at an error whose status has
 * historically been misread as a permission problem.
 */
@Configuration
class ApiDocsRoutingConfig implements WebMvcConfigurer {

  private static final String SWAGGER_UI = "/swagger-ui/index.html";

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // No loop: springdoc owns /swagger-ui (no slash) and redirects it to the same target.
    registry.addRedirectViewController("/swagger-ui/", SWAGGER_UI);
    registry.addRedirectViewController("/swagger-ui.html", SWAGGER_UI);
  }
}
