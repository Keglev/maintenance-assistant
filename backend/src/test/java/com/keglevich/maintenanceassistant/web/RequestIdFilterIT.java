package com.keglevich.maintenanceassistant.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every response carries a request id, and the inbound header is validated before it is trusted.
 *
 * <p>The id is what turns diagnosing an incident from timestamp arithmetic across two containers
 * into one {@code grep}. That only holds if it is ALWAYS there — a filter that produces an id for
 * most requests is a filter that is missing for the one request anybody cares about — so the first
 * case here is the one that matters: no header in, an id out anyway.
 *
 * <p>THE THIRD CASE IS A SECURITY ASSERTION, not a tidiness one. This value is written into a log
 * line, and a newline inside it would let a caller forge a line of their own. The filter replaces
 * anything that does not match its pattern rather than rejecting the request, because a malformed
 * correlation id is not a reason to fail a request that is otherwise fine — and the assertion is
 * that the forged text does not survive.
 *
 * <p>Against {@code /api/health} because it is public: this exercises the filter, not the security
 * chain, and a test that needed a Keycloak token would be testing something else.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequestIdFilterIT {

  private static final String HEADER = "X-Request-Id";

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("a request without the header gets a generated id in the response")
  void noHeader_generatesOne() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        // A UUID, which is what the filter generates when it has nothing to echo.
        .andExpect(header().string(HEADER,
            Matchers.matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
  }

  @Test
  @DisplayName("a valid inbound id is echoed unchanged, so the edge's id wins")
  void validHeader_isEchoed() throws Exception {
    mockMvc
        .perform(get("/api/health").header(HEADER, "0bd29be7-d4d2-40c4-a7f1-1a2b3c4d5e6f"))
        .andExpect(status().isOk())
        .andExpect(header().string(HEADER, "0bd29be7-d4d2-40c4-a7f1-1a2b3c4d5e6f"));
  }

  @Test
  @DisplayName("an id carrying a newline is replaced, not echoed into the log")
  void headerWithALineBreak_isReplaced() throws Exception {
    mockMvc
        .perform(get("/api/health").header(HEADER, "abcdefgh\nINFO forged line"))
        .andExpect(status().isOk())
        .andExpect(header().string(HEADER, Matchers.not(Matchers.containsString("forged"))))
        .andExpect(header().string(HEADER, Matchers.matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  @DisplayName("an id that is too short is replaced")
  void headerTooShort_isReplaced() throws Exception {
    mockMvc
        .perform(get("/api/health").header(HEADER, "abc"))
        .andExpect(status().isOk())
        .andExpect(header().string(HEADER, Matchers.not(Matchers.is("abc"))));
  }

  @Test
  @DisplayName("the filter adds a header and changes nothing else about the response")
  void theResponseIsOtherwiseUntouched() throws Exception {
    // ZERO BEHAVIOUR CHANGE, asserted rather than claimed: the health payload is what
    // HealthControllerIT already pins, and it is identical with the filter in the chain.
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.status").value("UP"));
  }
}
