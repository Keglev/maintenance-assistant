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

    // The two links out of Swagger UI live in the description because springdoc has no property
    // for a topbar link. They are the reader's only way back to the application and to the
    // documentation site, and being data rather than an asset they are silently deletable — so
    // the build asserts they are still served. This proves the CONFIGURATION, not the RENDERING:
    // a Swagger UI that stopped rendering markdown would still pass here.
    assertThat(spec)
        .contains("https://maintenance.smartsupply.com.de/")
        .contains("https://keglev.github.io/maintenance-assistant/");

    // The one described field in this API, and the description is the whole point of it: an
    // OPTIONAL field that appears on some answers and not others is unreadable from the payload
    // alone, so a client integrator has to be told when to expect it. Asserted because the
    // annotation carrying it is deletable without breaking a single other test, and a published
    // document that stops explaining an optional field is the documentation defect this
    // repository keeps finding in prose (REFACTOR-STANDARDS, DOCS).
    assertThat(spec)
        .contains("\"degradedFrom\"")
        .contains("\"TRUNCATED\"")
        .contains("Present only when retrieval selected Mode A");

    Files.createDirectories(OUTPUT.getParent());
    Files.writeString(OUTPUT, spec);

    assertThat(OUTPUT).isNotEmptyFile();
  }
}
