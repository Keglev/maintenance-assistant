package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.query.QueryAnswer;
import com.keglevich.maintenanceassistant.query.QueryRole;
import com.keglevich.maintenanceassistant.query.QueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may ask, and what a failure looks like on the wire.
 *
 * <p>The query module itself is tested elsewhere and is stubbed here on purpose: these assertions
 * are about the two things only the web layer decides — which roles reach the endpoint at all, and
 * which HTTP status each failure becomes. Both are the kind of rule that is easy to state in a
 * javadoc and get wrong in an annotation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueryControllerIT {

    private static final UUID MACHINE = UUID.fromString("0f9c5b01-0000-4000-8000-000000000001");
    private static final String BODY = """
            {"question":"Presse kommt nicht auf Druck, E-47","machineId":"%s"}""".formatted(MACHINE);

    @Autowired
    private MockMvc mockMvc;

    /** The production converter, so the test asserts the role mapping the application really runs. */
    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private QueryService queries;

    @Test
    @DisplayName("POST /api/query is rejected without a token")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin is refused — it is an IT role with no maintenance question to ask")
    void adminHasNoQueryAccess() throws Exception {
        mockMvc.perform(ask("admin", "admin")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an operator is answered, and the role comes from the token")
    void anOperatorIsAnswered() throws Exception {
        when(queries.ask(any(), eq(MACHINE), eq(QueryRole.OPERATOR), any()))
                .thenReturn(new QueryAnswer(QueryAnswer.AnswerMode.A, "Belegt. [P1]", "de",
                        List.of(new QueryAnswer.Claim("Belegt.", "P1")),
                        List.of(new QueryAnswer.Citation("P1", UUID.randomUUID(),
                                "E-47 Druckabfall im Presshub", "E-47", LocalDate.of(2024, 10, 8), 0.695))));

        mockMvc.perform(ask("operator", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("A"))
                .andExpect(jsonPath("$.language").value("de"))
                .andExpect(jsonPath("$.citations[0].errorCode").value("E-47"))
                .andExpect(jsonPath("$.claims[0].source").value("P1"));
    }

    @Test
    @DisplayName("a techniker is answered too")
    void aTechnikerIsAnswered() throws Exception {
        when(queries.ask(any(), eq(MACHINE), eq(QueryRole.TECHNIKER), any()))
                .thenReturn(new QueryAnswer(QueryAnswer.AnswerMode.B,
                        "Kein Protokoll deckt diesen Fall ab.", "de", List.of(), List.of()));

        mockMvc.perform(ask("techniker", "techniker"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("B"))
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    @DisplayName("the per-user rate limit answers 429 with Retry-After")
    void rateLimitIsReportedAsRetryable() throws Exception {
        when(queries.ask(any(), any(), any(), any()))
                .thenThrow(new QueryServiceExceptions().rateLimited());

        mockMvc.perform(ask("operator", "operator"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "6"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Try again in")));
    }

    @Test
    @DisplayName("an exhausted daily budget answers 503, and says answering resumes tomorrow")
    void anExhaustedBudgetIsReportedGracefully() throws Exception {
        when(queries.ask(any(), any(), any(), any()))
                .thenThrow(new QueryServiceExceptions().budgetExhausted());

        mockMvc.perform(ask("techniker", "techniker"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("today's answer limit")));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder ask(
            String username, String... realmRoles) {
        return post("/api/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .with(authentication(jwtAuthenticationConverter.convert(keycloakToken(username, realmRoles))));
    }

    /** A token shaped like the one Keycloak issues for the maintenance realm. */
    private static Jwt keycloakToken(String username, String... realmRoles) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("iss", "http://localhost:8081/realms/maintenance")
                .claim("aud", List.of("backend"))
                .claim("azp", "frontend")
                .claim("sub", "00000000-0000-0000-0000-0000000000" + username.length())
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
    }

    /**
     * The module's exceptions have package-private constructors, which is correct — nothing outside
     * the query module should be able to invent a rate-limit refusal. Reflection here rather than
     * widening them for a test.
     */
    private static final class QueryServiceExceptions {

        QueryService.RateLimitedException rateLimited() throws Exception {
            var constructor = QueryService.RateLimitedException.class
                    .getDeclaredConstructor(long.class, String.class);
            constructor.setAccessible(true);
            return constructor.newInstance(6L, "Too many questions in a short time "
                    + "(limit 10 per minute). Try again in 6 seconds.");
        }

        QueryService.BudgetExhaustedException budgetExhausted() throws Exception {
            var constructor = QueryService.BudgetExhaustedException.class
                    .getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            return constructor.newInstance("daily chat budget reached: 400 of 400 calls used today");
        }
    }
}
