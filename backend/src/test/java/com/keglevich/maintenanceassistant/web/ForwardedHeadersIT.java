package com.keglevich.maintenanceassistant.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The application is behind a TLS-terminating reverse proxy, and this proves it knows.
 *
 * <p>Caddy answers on 443 and forwards plain HTTP to {@code backend:8080}, setting
 * {@code X-Forwarded-Proto}, {@code -Host} and {@code -For} as it goes. Spring ignores those headers
 * unless {@code server.forward-headers-strategy} says otherwise, so every URL the application
 * DERIVES FROM THE REQUEST is built from what the container saw rather than from what the client
 * sent — {@code http://} and, without a Host header of its own, the internal name.
 *
 * <p>The symptom that surfaced it was the published API document: {@code /v3/api-docs} advertised
 * {@code "url": "http://maintenance.smartsupply.com.de"} in production, over a site that is
 * HTTPS-only. springdoc does not hardcode that value — it asks the framework what the request's
 * base URL was — which is why the fix is one property and NOT a hardcoded {@code servers} entry: a
 * literal URL would paper over the same defect everywhere else it shows, in redirect {@code
 * Location} headers and in any other absolute link the application builds.
 *
 * <p>THROUGH A REAL SERVLET CONTAINER, ON A REAL PORT, and deliberately not through MockMvc. The
 * mechanism under test IS a servlet filter — Spring's {@code ForwardedHeaderFilter}, registered by
 * the property — so a harness that assembles its own filter chain could report either answer for
 * reasons that have nothing to do with the deployed application. {@link ApiDocsEntrypointIT} is
 * here for the same reason and records the defect that taught it.
 *
 * <p>VACUITY: remove the property from application.yml and the first case fails with
 * {@code http://localhost:<port>}, which is the assertion earning its keep. Verified 2026-08-25.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ForwardedHeadersIT {

  private static final String PUBLIC_HOST = "maintenance.smartsupply.com.de";

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("servers[0].url follows X-Forwarded-Proto and X-Forwarded-Host")
  void serverUrl_forwardedHeadersPresent_usesPublicHttpsUrl() throws Exception {
    assertThat(firstServerUrl(HttpRequest.newBuilder(apiDocs())
        .header("X-Forwarded-Proto", "https")
        .header("X-Forwarded-Host", PUBLIC_HOST)))
        .isEqualTo("https://" + PUBLIC_HOST);
  }

  /**
   * The other half of the contract, and the reason the property is safe on the default profile: a
   * request that carries no forwarded headers — a developer's `mvn spring-boot:run`, or the Angular
   * dev-server proxy, which sets none — is described by itself. The filter falls back to the actual
   * request rather than inventing a scheme.
   */
  @Test
  @DisplayName("servers[0].url describes the actual request when no proxy headers are sent")
  void serverUrl_noForwardedHeaders_usesTheActualRequest() throws Exception {
    assertThat(firstServerUrl(HttpRequest.newBuilder(apiDocs())))
        .isEqualTo("http://localhost:" + port);
  }

  private URI apiDocs() {
    return URI.create("http://localhost:" + port + "/v3/api-docs");
  }

  private String firstServerUrl(HttpRequest.Builder request) throws IOException, InterruptedException {
    HttpResponse<String> response = client.send(request.GET().build(), BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);

    JsonNode servers = mapper.readTree(response.body()).path("servers");
    assertThat(servers).as("the document carries a servers entry to assert on").isNotEmpty();
    return servers.get(0).path("url").asText();
  }
}
