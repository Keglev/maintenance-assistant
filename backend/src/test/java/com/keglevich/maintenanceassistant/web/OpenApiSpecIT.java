package com.keglevich.maintenanceassistant.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // ADR-011's two additions to the contract, asserted for the same reason as degradedFrom above:
    // an OPTIONAL field and a new read-only path are both deletable without another test noticing,
    // and both are things an integrator has to be told about rather than discover.
    assertThat(spec)
        .contains("/api/machines/{machineNo}/examples")
        .contains("\"protocolCount\"")
        .contains("Live protocols only");

    // 413 IS ADVERTISED WHERE IT CAN HAPPEN, AND NOWHERE ELSE. UploadSizeExceededAdvice has to be
    // global — the container refuses an oversized multipart before a handler is resolved — and
    // springdoc used to merge its status into all 19 operations, thirteen of them GETs that carry
    // no request body. The advice is @Hidden from that merge and the upload declares the status
    // itself. Asserted from the document rather than from the annotations, because the defect was
    // invisible in the code and obvious in the spec.
    JsonNode spec413 = new ObjectMapper().readTree(spec);
    assertThat(responseCodes(spec413, "/api/machines/{machineNo}/examples"))
        .as("a GET carries no request body and can never answer 413")
        .doesNotContain("413");
    assertThat(responseCodes(spec413, "/api/machines"))
        .as("nor can the machine list")
        .doesNotContain("413");
    assertThat(spec413.at("/paths/~1api~1protocols/post/responses/413/description").asText())
        .as("the one operation that CAN answer 413 says so, and says what the body carries")
        .contains("spring.servlet.multipart.max-file-size");
    assertThat(spec413.at("/paths/~1api~1protocols/post/responses/413/content"
            + "/application~1json/schema/$ref").asText())
        .isEqualTo("#/components/schemas/FileTooLarge");

    Files.createDirectories(OUTPUT.getParent());
    Files.writeString(OUTPUT, spec);

    assertThat(OUTPUT).isNotEmptyFile();
  }

  /** The response codes one operation declares, read from the document rather than the code. */
  private static java.util.Set<String> responseCodes(JsonNode spec, String path) {
    JsonNode responses = spec.at("/paths/" + path.replace("~", "~0").replace("/", "~1")
        + "/get/responses");
    assertThat(responses.isMissingNode()).as("no GET declared at %s", path).isFalse();
    java.util.Set<String> codes = new java.util.TreeSet<>();
    responses.fieldNames().forEachRemaining(codes::add);
    return codes;
  }
}
