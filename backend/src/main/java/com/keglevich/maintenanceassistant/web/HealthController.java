package com.keglevich.maintenanceassistant.web;

import io.swagger.v3.oas.annotations.Operation;
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
  HealthResponse health() {
    return new HealthResponse("UP", version);
  }

  record HealthResponse(String status, String version) {}
}
