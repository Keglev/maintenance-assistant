package com.keglevich.maintenanceassistant.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness endpoint.
 *
 * <p>Deliberately unauthenticated: it is what a load balancer, the compose healthcheck and a
 * recruiter clicking the demo URL hit first, and none of them hold a token. It reports no
 * information an anonymous caller could not already infer.
 *
 * <p>THIS IS THE ONLY UNAUTHENTICATED READ IN THE API, and the spec now says so rather than
 * leaving it to be inferred. {@link OpenApiConfig} declares the bearer requirement globally, so
 * this operation has to CLEAR it explicitly — an empty {@code @SecurityRequirements} is the
 * opt-out, and it is deliberately the only one in the codebase. A second one would be a security
 * decision, and the annotation is where it would have to become visible.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "System", description = "Public service information")
class HealthController {

  private final String version;

  HealthController(ObjectProvider<BuildProperties> buildProperties) {
    // Written by the spring-boot-maven-plugin build-info goal; absent when the class is started
    // straight from an IDE, which is not worth failing over.
    this.version = buildProperties.getIfAvailable() != null
        ? buildProperties.getObject().getVersion()
        : "dev";
  }

  @GetMapping("/health")
  @Operation(summary = "Service status and version", description = "Public — no token required.")
  // Clears the global bearer requirement from OpenApiConfig for this operation only.
  @SecurityRequirements
  HealthResponse health() {
    return new HealthResponse("UP", version);
  }

  /**
   * What an anonymous caller may know: that the service is up, and which build answered.
   *
   * <p>{@code version} is BuildProperties, written by the build-info goal, and reads {@code dev}
   * from an IDE start. It is the APPLICATION version and not the API contract generation, which is
   * the {@code v1} in the OpenAPI info block — see OpenApiConfig for why those are two numbers.
   */
  record HealthResponse(String status, String version) {}
}
