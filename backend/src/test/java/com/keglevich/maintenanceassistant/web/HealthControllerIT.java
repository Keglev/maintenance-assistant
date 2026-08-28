package com.keglevich.maintenanceassistant.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one endpoint that answers without a token, and the test that keeps it that way.
 *
 * <p>Public is a REQUIREMENT and not an oversight: a load balancer, the compose healthcheck and a
 * recruiter opening the demo URL all reach it holding nothing. A security change that quietly
 * protected everything would be invisible until the container reported itself unhealthy in
 * production, so the anonymous case is asserted here rather than assumed.
 *
 * <p>OUT OF SCOPE: that the SPEC says so too — {@link OpenApiSpecIT} asserts health is the only
 * operation clearing the global bearer requirement — and HEAD, which
 * {@link ApiDocsEntrypointIT} covers because it took a real container to see.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerIT {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("GET /api/health is readable without a token")
  void healthIsPublic() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        // The version comes from build-info.properties; only its presence is contractual.
        .andExpect(jsonPath("$.version").isNotEmpty());
  }
}
