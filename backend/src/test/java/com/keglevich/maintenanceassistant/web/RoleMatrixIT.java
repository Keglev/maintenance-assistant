package com.keglevich.maintenanceassistant.web;

import com.keglevich.maintenanceassistant.ingestion.ProtocolApprovalService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolDocumentService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolEditService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolIntakeService;
import com.keglevich.maintenanceassistant.ingestion.ProtocolModerationService;
import com.keglevich.maintenanceassistant.ingestion.UploadRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * FOUR EYES, AS A STRUCTURAL PROPERTY OF THE ROLE SPLIT.
 *
 * <h2>What this replaces, and why it is not the same shape</h2>
 *
 * <p>Until 2026-08-15 the four-eyes rule had a second line: {@code ProtocolApprovalService} compared
 * the approver's username against {@code uploaded_by} and against the newest {@code EDIT} actor, and
 * refused. That check was correct when it was written (#53, while an administrator still held the
 * edit) and became incorrect when Option B split the roles (#54): from then on it could not fire on
 * anything a live role can produce, and the only thing it DID fire on was data written before the
 * split — an administrator's old drill correction. Carlos hit exactly that in production on
 * "Fehlercode x-99" and was shown a refusal for a rule nobody had broken.
 *
 * <p>Deleting it would have left the property resting on three {@code @PreAuthorize} annotations
 * with nothing watching them, and <b>a rule enforced in one place is one edit away from being
 * gone</b>. So the guard moved rather than disappeared — from a banner shown to one user at runtime,
 * to a build failure in CI before it ships, covering every endpoint instead of one pair.
 *
 * <h2>How the matrix is derived, and why not by reflection over the annotations</h2>
 *
 * <p><b>Every cell below is measured by making the request.</b> For each endpoint and each realm
 * role the test issues a real call through the full security filter chain and reads whether it was
 * refused. It does not read {@code @PreAuthorize} strings, and that is deliberate:
 *
 * <ul>
 *   <li><b>Parsing SpEL is a re-implementation.</b> A regex over {@code hasAnyRole('A','B')} would
 *       have to grow its own understanding of {@code hasRole} versus {@code hasAnyRole}, of the
 *       {@code ROLE_} prefix, of {@code isAuthenticated()}, and of anything a future expression
 *       uses. The day someone writes a rule the parser does not model, the test would go quietly
 *       green on an expression it did not understand — the worst possible failure for a guard.</li>
 *   <li><b>An annotation is not the effective rule.</b> {@code ModerationController} carries a
 *       class-level {@code hasRole('ADMIN')} that method-level annotations REPLACE. Reflection over
 *       one method's annotation cannot see that; a request can. The same goes for anything
 *       {@code SecurityConfig} does to the chain.</li>
 *   <li><b>A hand-written table is not evidence.</b> Restating the seven-row matrix in a second file
 *       would only assert that two files agree, and both are written by the same person on the same
 *       afternoon.</li>
 * </ul>
 *
 * <p>So the reading is: <b>403 means refused, any other status means the method was entered and
 * therefore authorised.</b> The services are mocked, so nothing downstream can produce a 403 of its
 * own — a 400 for a missing body or a 404 for an unknown id both mean the call got past the guard,
 * which is the only question being asked.
 *
 * <p>Reflection is still used for one job it is good at: {@link #everyEndpointIsInTheMatrix()} walks
 * Spring's own handler mapping and fails if a controller gains a route this file does not probe. So
 * the matrix cannot silently stop being complete either.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleMatrixIT {

    private static final UUID PROTOCOL = UUID.fromString("0f9c5b02-0000-4000-8000-000000000001");

    /** The realm's four roles, as {@code realm_access.roles} spells them. */
    private static final List<String> ROLES =
            List.of("operator", "techniker", "schichtleiter", "admin");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    /**
     * Spring MVC's own route table, by name.
     *
     * <p>Qualified because actuator contributes a second {@code RequestMappingHandlerMapping} of its
     * own ({@code controllerEndpointHandlerMapping}) and an unqualified injection is ambiguous. This
     * is the one that holds the application's controllers.
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // Mocked so that nothing past the security layer can fail for a reason of its own. What is under
    // test is who gets IN, never what happens next.
    @MockitoBean
    private ProtocolModerationService moderation;
    @MockitoBean
    private ProtocolEditService edits;
    @MockitoBean
    private ProtocolDocumentService documents;
    @MockitoBean
    private ProtocolApprovalService approvals;
    @MockitoBean
    private ProtocolIntakeService intake;
    @MockitoBean
    private UploadRateLimiter rateLimiter;

    /**
     * One thing a caller can try to do, and the request that tries it.
     *
     * @param authority  what this endpoint lets a role DO, in the vocabulary the four-eyes property
     *                   is stated in — see {@link #CORPUS_WRITE} and {@link #APPROVE}
     */
    private record Endpoint(String name, String authority,
                            Function<RequestPostProcessor, RequestBuilder> request) {
    }

    /** Changing what the corpus says: creating a protocol, or rewriting one. */
    private static final String CORPUS_WRITE = "write to the corpus";
    /** Vouching for what the corpus says. */
    private static final String APPROVE = "approve";
    /** Everything else — reads, and removal. Not part of the four-eyes pair. */
    private static final String OTHER = "other";

    private static List<Endpoint> endpoints() {
        return List.of(
                // The two acts that CHANGE what the corpus says.
                new Endpoint("POST /api/protocols", CORPUS_WRITE,
                        as -> multipart("/api/protocols")
                                .file(new org.springframework.mock.web.MockMultipartFile(
                                        "file", "p.txt", "text/plain", "Symptom:\n".getBytes()))
                                .param("machine", "PR-03")
                                .param("type", "STOERUNG")
                                .param("title", "T")
                                .with(as)),
                new Endpoint("PUT /api/moderation/protocols/{id}", CORPUS_WRITE,
                        as -> put("/api/moderation/protocols/{id}", PROTOCOL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"T\",\"content\":\"C\",\"comment\":\"k\"}")
                                .with(as)),

                // The act that VOUCHES for what the corpus says.
                new Endpoint("PUT /api/moderation/protocols/{id}/approval", APPROVE,
                        as -> put("/api/moderation/protocols/{id}/approval", PROTOCOL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approved\":true}")
                                .with(as)),

                // The rest of the moderation surface — the table in the controller javadoc.
                new Endpoint("GET /api/moderation/protocols", OTHER,
                        as -> get("/api/moderation/protocols").with(as)),
                new Endpoint("GET /api/moderation/protocols/{id}/document", OTHER,
                        as -> get("/api/moderation/protocols/{id}/document", PROTOCOL).with(as)),
                new Endpoint("GET /api/moderation/protocols/{id}/history", OTHER,
                        as -> get("/api/moderation/protocols/{id}/history", PROTOCOL).with(as)),
                new Endpoint("GET /api/moderation/protocols/{id}/similar", OTHER,
                        as -> get("/api/moderation/protocols/{id}/similar", PROTOCOL).with(as)),
                new Endpoint("DELETE /api/moderation/protocols/{id}", OTHER,
                        as -> delete("/api/moderation/protocols/{id}", PROTOCOL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"comment\":\"weg\"}")
                                .with(as)),
                new Endpoint("GET /api/moderation/protocols/deleted", OTHER,
                        as -> get("/api/moderation/protocols/deleted").with(as)),
                new Endpoint("GET /api/moderation/protocols/deleted/{id}/document", OTHER,
                        as -> get("/api/moderation/protocols/deleted/{id}/document", PROTOCOL).with(as)),

                // The shop floor's own surface, so a widening there is caught too.
                new Endpoint("POST /api/query", OTHER,
                        as -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/query")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"q\",\"machineId\":\"" + PROTOCOL + "\"}")
                                .with(as)),
                new Endpoint("GET /api/protocols/{id}/document", OTHER,
                        as -> get("/api/protocols/{id}/document", PROTOCOL).with(as)),
                new Endpoint("GET /api/protocols/mine", OTHER,
                        as -> get("/api/protocols/mine").with(as)),
                new Endpoint("GET /api/machines", OTHER,
                        as -> get("/api/machines").with(as)),
                // ADR-011. Open to all four roles ON PURPOSE — these are QUESTIONS, and what an
                // operator may be TOLD is filtered on the answer path. That is exactly the kind of
                // deliberate widening this matrix exists to keep visible rather than to forbid.
                new Endpoint("GET /api/machines/{machineNo}/examples", OTHER,
                        as -> get("/api/machines/{machineNo}/examples", "PR-03").with(as)),

                // The ingestion admin surface. Not part of the four-eyes pair — it re-runs indexing,
                // it does not change what a protocol SAYS — but a widening here is still a widening.
                new Endpoint("GET /api/ingestion/status", OTHER,
                        as -> get("/api/ingestion/status").with(as)),
                new Endpoint("POST /api/ingestion/backlog", OTHER,
                        as -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/ingestion/backlog").with(as)));
    }

    // -------------------------------------------------------------------------------------------
    // THE PROPERTY
    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("NO ROLE may both write to the corpus and approve — this IS four eyes")
    void noRoleHoldsBothCorpusWriteAndApprove() {
        Map<String, Set<String>> allowed = measureMatrix();
        List<String> violations = new ArrayList<>();

        for (String role : ROLES) {
            List<String> writes = endpoints().stream()
                    .filter(e -> CORPUS_WRITE.equals(e.authority()))
                    .filter(e -> allowed.get(role).contains(e.name()))
                    .map(Endpoint::name)
                    .toList();
            boolean approves = endpoints().stream()
                    .filter(e -> APPROVE.equals(e.authority()))
                    .anyMatch(e -> allowed.get(role).contains(e.name()));

            if (approves && !writes.isEmpty()) {
                violations.add("role %s can both %s (%s) and %s — FOUR EYES IS GONE: the same person "
                        .formatted(role.toUpperCase(java.util.Locale.ROOT), CORPUS_WRITE,
                                String.join(" and ", writes), APPROVE)
                        + "could write a protocol and then vouch for it. The trust chain rests on "
                        + "these authorities being held by DIFFERENT roles (decision 3 of "
                        + "2026-08-11, ADR-006). If this widening is intended, the chain has to be "
                        + "redesigned — not this test relaxed.");
            }
        }

        assertThat(violations).as(String.join("\n", violations)).isEmpty();
    }

    @Test
    @DisplayName("the whole matrix, so an accidental widening of ANY endpoint is caught")
    void theMatrixIsWhatTheJavadocSays() {
        /*
         * THE SEVEN-ROW TABLE IN ModerationController'S JAVADOC, PLUS THE SHOP FLOOR, as measured
         * rather than as copied. This catches the widenings the four-eyes assertion above cannot:
         * a Techniker gaining the archive, an Operator gaining the corpus list, an administrator
         * regaining the edit.
         *
         * Expressed as "who is allowed", because that is the shorter and less ambiguous half — the
         * refused set is everything else.
         */
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("POST /api/protocols", Set.of("techniker", "schichtleiter"));
        expected.put("PUT /api/moderation/protocols/{id}", Set.of("schichtleiter"));
        expected.put("PUT /api/moderation/protocols/{id}/approval", Set.of("admin"));
        expected.put("GET /api/moderation/protocols", Set.of("admin", "schichtleiter"));
        expected.put("GET /api/moderation/protocols/{id}/document", Set.of("admin", "schichtleiter"));
        expected.put("GET /api/moderation/protocols/{id}/history", Set.of("admin", "schichtleiter"));
        expected.put("GET /api/moderation/protocols/{id}/similar", Set.of("admin"));
        expected.put("DELETE /api/moderation/protocols/{id}", Set.of("admin"));
        expected.put("GET /api/moderation/protocols/deleted", Set.of("admin"));
        expected.put("GET /api/moderation/protocols/deleted/{id}/document", Set.of("admin"));
        expected.put("POST /api/query", Set.of("operator", "techniker", "schichtleiter"));
        expected.put("GET /api/protocols/{id}/document", Set.of("operator", "techniker", "schichtleiter"));
        // Both writers, since 2026-08-28: decision 3 gave the Techniker the upload in v1.2.0 and
        // this list is what makes a 202 legible, so a writer who cannot read it cannot see their
        // own failed indexing. Self-scoped by preferred_username, so the second role reads its own
        // rows and nobody else's.
        expected.put("GET /api/protocols/mine", Set.of("techniker", "schichtleiter"));
        expected.put("GET /api/machines", Set.of("operator", "techniker", "schichtleiter", "admin"));
        // All four, deliberately (ADR-011): an example is a QUESTION, and the answer path is where
        // an operator's view of a protocol is narrowed. Note what this row does NOT do — it gives
        // nobody access to protocol TEXT, so it cannot widen the four-eyes pair above.
        expected.put("GET /api/machines/{machineNo}/examples",
                Set.of("operator", "techniker", "schichtleiter", "admin"));

        Map<String, Set<String>> actual = new LinkedHashMap<>();
        Map<String, Set<String>> allowed = measureMatrix();
        for (Endpoint endpoint : endpoints()) {
            actual.put(endpoint.name(), new TreeSet<>(ROLES.stream()
                    .filter(role -> allowed.get(role).contains(endpoint.name()))
                    .toList()));
        }

        for (Map.Entry<String, Set<String>> row : expected.entrySet()) {
            assertThat(actual.get(row.getKey()))
                    .as("%s — the roles that may reach it changed. If that is intended, change this "
                            + "row AND check what it does to four eyes; if it is not, an "
                            + "@PreAuthorize was widened by accident.", row.getKey())
                    .isEqualTo(new TreeSet<>(row.getValue()));
        }
    }

    @Test
    @DisplayName("every probe actually reaches the guard — a malformed request must not read as 'allowed'")
    void everyProbeActuallyReachesTheGuard() {
        /*
         * THE SOUNDNESS CHECK, AND IT IS HERE BECAUSE THIS TEST WAS BRIEFLY WRONG IN EXACTLY THIS
         * WAY. The upload probe first sent `machineNo` and `protocolType`; the controller declares
         * `machine` and `type`. Spring MVC resolves handler arguments BEFORE the method-security
         * interceptor runs, so the missing required parameter produced a 400 for every role — and
         * a 400 is "not 403", which this file reads as ALLOWED. The matrix duly reported that all
         * four roles could upload, including the administrator, and the four-eyes assertion failed
         * for a reason that had nothing to do with the application.
         *
         * A guard that can be defeated by a typo in its own request builder is not a guard. So:
         * a probe must DISCRIMINATE. If an endpoint answers every role with the same 4xx that is
         * not 403, the request never reached the rule and the cell means nothing — fail, loudly,
         * rather than report a matrix nobody should trust.
         *
         * An endpoint genuinely open to everyone (GET /api/machines) answers 2xx for all four and is
         * fine. One that refuses somebody shows a 403 somewhere. Only the uniform-4xx shape is
         * evidence of a broken probe, which is what makes this check non-circular: it says nothing
         * about WHO should be allowed.
         */
        List<String> unsound = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            Set<Integer> statuses = new LinkedHashSet<>();
            ROLES.forEach(role -> statuses.add(statusFor(endpoint, role)));

            boolean sawARefusal = statuses.contains(HttpStatus.FORBIDDEN.value());
            boolean everyoneGotAClientError = statuses.stream()
                    .allMatch(status -> status >= 400 && status < 500);
            if (!sawARefusal && everyoneGotAClientError) {
                unsound.add("%s answered every role with %s — no 403 anywhere, so the request is "
                        .formatted(endpoint.name(), statuses)
                        + "being rejected BEFORE the security rule (a wrong parameter name, a bad "
                        + "body, an unmapped verb). Its row in the matrix is meaningless until the "
                        + "request is fixed.");
            }
        }

        assertThat(unsound).as(String.join("\n", unsound)).isEmpty();
    }

    @Test
    @DisplayName("every secured route is in the matrix — a new endpoint cannot slip past it")
    void everyEndpointIsInTheMatrix() {
        /*
         * The completeness half, and the one job reflection is genuinely good at. Without it, adding
         * a controller method would leave the matrix silently partial: the two tests above would
         * still pass while an unprobed endpoint let anyone in.
         *
         * Only this application's own controllers, and only the API surface: springdoc and actuator
         * bring their own handlers, and the public paths (/api/health, the error dispatch) are the
         * filter chain's business rather than a role's.
         */
        Set<String> probed = new LinkedHashSet<>();
        endpoints().forEach(e -> probed.add(e.name()));

        List<String> unprobed = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry
                : handlerMapping.getHandlerMethods().entrySet()) {
            Method method = entry.getValue().getMethod();
            String type = method.getDeclaringClass().getName();
            if (!type.startsWith("com.keglevich.maintenanceassistant")) {
                continue;
            }
            // Exception handlers are not routes; they carry @PreAuthorize only so they can render
            // for a caller the class-level rule would otherwise refuse (see ModerationController).
            if (method.isAnnotationPresent(org.springframework.web.bind.annotation.ExceptionHandler.class)) {
                continue;
            }
            for (String path : paths(entry.getKey())) {
                if (PUBLIC_PATHS.contains(path)) {
                    continue;
                }
                String verb = entry.getKey().getMethodsCondition().getMethods().stream()
                        .findFirst().map(Enum::name).orElse("GET");
                String name = verb + " " + path;
                if (!probed.contains(name)) {
                    unprobed.add(name + "  (" + method.getDeclaringClass().getSimpleName()
                            + "." + method.getName() + ")");
                }
            }
        }

        assertThat(unprobed)
                .as("these routes exist and no role matrix covers them, so a widening on any of "
                        + "them would go unnoticed. Add them to RoleMatrixIT.endpoints():\n%s",
                        String.join("\n", unprobed))
                .isEmpty();
    }

    /** Public by design — the filter chain permits them, so no role has anything to say about them. */
    private static final Set<String> PUBLIC_PATHS =
            Set.of("/api/health", "/api/hello", "/error");

    // -------------------------------------------------------------------------------------------

    /** For every role, the endpoints it was NOT refused. Measured, one request per cell. */
    private Map<String, Set<String>> measureMatrix() {
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        for (String role : ROLES) {
            Set<String> reachable = new LinkedHashSet<>();
            for (Endpoint endpoint : endpoints()) {
                if (!isForbidden(endpoint, role)) {
                    reachable.add(endpoint.name());
                }
            }
            allowed.put(role, reachable);
        }
        return allowed;
    }

    /**
     * Whether this role is refused this endpoint.
     *
     * <p>403 and nothing else. Every service is mocked, so a 400, a 404 or a 200 all mean the same
     * thing here: the request got past the guard.
     */
    private boolean isForbidden(Endpoint endpoint, String role) {
        return statusFor(endpoint, role) == HttpStatus.FORBIDDEN.value();
    }

    /** The status this role gets from this endpoint. One request; nothing is cached. */
    private int statusFor(Endpoint endpoint, String role) {
        try {
            return mockMvc.perform(endpoint.request().apply(as(role)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception e) {
            // A handler that threw AFTER the guard let it in is still "allowed" for this question,
            // and 500 is not 403 — which is what the caller reads.
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
    }

    private static List<String> paths(RequestMappingInfo info) {
        return new ArrayList<>(info.getPathPatternsCondition() == null
                ? List.of()
                : info.getPathPatternsCondition().getPatternValues());
    }

    private RequestPostProcessor as(String role) {
        return authentication(jwtAuthenticationConverter.convert(keycloakToken(role, role)));
    }

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
}
