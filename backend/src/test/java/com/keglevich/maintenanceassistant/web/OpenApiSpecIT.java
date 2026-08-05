package com.keglevich.maintenanceassistant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exports the OpenAPI specification to {@code target/openapi/openapi.json}.
 *
 * <p>Writing the spec from a test rather than from the springdoc Maven plugin keeps CI free of
 * external dependencies: the plugin needs a started application (and therefore a reachable
 * database and Keycloak), while this runs inside the existing test context where datasource and
 * Flyway auto-configuration are switched off and no token is ever validated.
 *
 * <p>The assertions make it a real test as well as a generator — an empty or pathless document
 * fails the build instead of silently publishing a useless file.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecIT {

  private static final Path OUTPUT = Path.of("target", "openapi", "openapi.json");

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("the OpenAPI document is served and written to target/openapi/openapi.json")
  void exportsOpenApiSpec() throws Exception {
    String spec = mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

    assertThat(spec).contains("\"openapi\"").contains("/api/health").contains("/api/hello");

    Files.createDirectories(OUTPUT.getParent());
    Files.writeString(OUTPUT, spec);

    assertThat(OUTPUT).isNotEmptyFile();
  }
}
