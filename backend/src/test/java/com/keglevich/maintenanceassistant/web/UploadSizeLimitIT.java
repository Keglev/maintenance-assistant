package com.keglevich.maintenanceassistant.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The container's upload size limit, exercised through a real servlet container.
 *
 * <p><b>Why this cannot be a MockMvc test.</b> An oversized multipart is refused by Tomcat while it
 * parses the request — before the dispatcher resolves a handler, and therefore before anything
 * MockMvc simulates. A MockMvc suite would pass with the limit unset, with the limit set to
 * anything, and with no handler for the failure at all; it simply cannot reach the code path. This
 * is the same shape as the HEAD defect recorded in PROJECT-PHASES: the test that could see it had to
 * be a real request against a real container.
 *
 * <p><b>The requests carry a token, and the first version of this test did not — which is how the
 * ordering got established.</b> An unauthenticated oversized upload answers <b>401, not 413</b>:
 * Spring Security's filter chain runs before the dispatcher resolves the multipart, so a stranger is
 * turned away before the size is ever considered. That is the right order (nothing is parsed on
 * behalf of someone who may not upload at all), but it means the size limit is only observable to a
 * caller who is allowed in — hence the stub decoder below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Small and explicit, so the test provokes the limit with a few KB rather than a quarter megabyte.
@TestPropertySource(properties = {
        "spring.servlet.multipart.max-file-size=8KB",
        "spring.servlet.multipart.max-request-size=16KB"
})
@Import(UploadSizeLimitIT.StubDecoder.class)
class UploadSizeLimitIT {

    private static final String BOUNDARY = "----maintenanceAssistantTestBoundary";

    /**
     * Turns the literal string {@code test-token} into a valid Schichtleiter token.
     *
     * <p>Needed because this test speaks real HTTP: {@code spring-security-test}'s request
     * post-processors only exist inside MockMvc, and the alternative is a live Keycloak. The token's
     * signature is never the subject here — the size limit is.
     */
    @TestConfiguration
    static class StubDecoder {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("iss", "http://localhost:8081/realms/maintenance")
                    .claim("sub", "00000000-0000-0000-0000-000000000001")
                    .claim("preferred_username", "schichtleiter")
                    .claim("realm_access", Map.of("roles", List.of("schichtleiter")))
                    .build();
        }
    }

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("an oversized upload answers 413 as JSON, not as the container's HTML page")
    void anOversizedUploadIsRefusedAsJson() throws Exception {
        // Comfortably past the 8 KB part limit configured above.
        String protocol = "Kein Druck an der Presse. ".repeat(2_000);

        HttpResponse<String> response = upload("protokoll.txt", protocol);

        assertThat(response.statusCode())
                .as("an oversized part must be refused with 413")
                .isEqualTo(413);
        // The body is what the upload view reads. A Tomcat HTML error page here would leave the
        // user with the generic failure message for a problem that has a precise explanation.
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("413 body must be JSON the client can read")
                .contains("application/json");
        assertThat(response.body())
                .contains("FILE_TOO_LARGE")
                // The limit itself, so the writer knows how much to cut rather than guessing. It
                // reads the configured property because the exception's own getMaxUploadSize()
                // answers -1 here — measured, and the reason the message is not built from it.
                .contains("8KB");
    }

    @Test
    @DisplayName("a normal-sized upload is not refused for its size")
    void aNormalUploadIsNotRefusedForSize() throws Exception {
        // The counterpart that makes the assertion above mean something: if every request answered
        // 413, the test above would pass while the limit was broken.
        HttpResponse<String> response = upload("protokoll.txt", "Symptom:\nKein Druck.\n");

        // What it DOES answer is deliberately not asserted. This context has no database, so the
        // intake service fails somewhere past the point this test cares about — and pinning that
        // status down here would make this test break for reasons that have nothing to do with
        // upload size. "Got past the size check" is the whole claim.
        assertThat(response.statusCode())
                .as("a few hundred bytes must not be refused as too large")
                .isNotEqualTo(413);
    }

    private HttpResponse<String> upload(String filename, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/protocols"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer test-token")
                .POST(BodyPublishers.ofByteArray(multipartBody(filename, content)))
                .timeout(Duration.ofSeconds(20))
                .build();
        return client.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * A multipart body built by hand.
     *
     * <p>Deliberately not a Spring test helper: the point of this test is the bytes a browser
     * actually puts on the wire and what Tomcat does with them, and a helper that constructs the
     * request through the framework would be testing the framework's own idea of the request.
     */
    private static byte[] multipartBody(String filename, String content) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(content.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));

        for (String[] field : new String[][]{
                {"machine", "PR-03"}, {"type", "STOERUNG"}, {"title", "E-47 Druckabfall"}}) {
            body.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(field[1].getBytes(StandardCharsets.UTF_8));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }
}
