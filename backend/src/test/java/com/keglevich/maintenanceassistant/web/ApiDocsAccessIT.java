package com.keglevich.maintenanceassistant.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The API documentation is public, and the paths a reader actually types resolve.
 *
 * <p>This exists because production served {@code /swagger-ui/index.html} perfectly while
 * {@code /swagger-ui} — the path a person types and the one the README publishes — answered 401.
 * There was no mapping for the bare path, and the error dispatch that followed was itself guarded,
 * so a plain 404 was reported as a permission problem. Both halves are asserted here: the docs are
 * reachable without a token, and a guarded endpoint is still guarded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiDocsAccessIT {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("GET /swagger-ui redirects into the UI without a token")
  void swaggerUiPathResolves() throws Exception {
    mockMvc
        .perform(get("/swagger-ui"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("swagger-ui")));
  }

  @Test
  @DisplayName("GET /v3/api-docs is readable without a token")
  void apiDocsArePublic() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("a path outside the permit list answers 401, whether or not a handler exists")
  void unlistedPathIsUnauthorized() throws Exception {
    // Authorization runs before dispatch, so an anonymous caller learns nothing about which paths
    // exist. Worth pinning because it is also the trap: /swagger-ui answered 401 while
    // /swagger-ui/index.html served fine, which reads as a permission bug and was a missing
    // mapping. A 401 here is the design; a 401 on a documented path is the defect.
    mockMvc.perform(get("/swagger-ui-typo")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the query endpoint still refuses an unauthenticated caller")
  void queryStaysGuarded() throws Exception {
    // The contrast that makes the permits above safe: opening the documentation opened nothing
    // else. This is the endpoint that costs a provider call.
    mockMvc
        .perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
