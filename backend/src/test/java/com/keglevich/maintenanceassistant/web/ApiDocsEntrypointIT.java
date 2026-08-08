package com.keglevich.maintenanceassistant.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The documentation entry point, exercised through a real servlet container by a real HTTP client.
 *
 * <p>Both halves of that sentence are the point. MockMvc is what let this defect ship: it does not
 * send HEAD the way a client does, and the security matcher treats HEAD as a method of its own, so
 * a suite that only ever issued GET could not see it. The JDK client is used rather than a Spring
 * test helper because it sends exactly the request {@code curl -I} sends and follows no redirect
 * unless told to — a followed redirect would hide the very status this asserts.
 *
 * <p>The reported symptom was {@code curl -sI https://…/swagger-ui} answering 401 while a browser
 * opened the same URL happily. {@code -I} sends HEAD; the matcher permitted GET only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiDocsEntrypointIT {

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  /**
   * Every spelling of the entry point a reader might type or bookmark. A redirect into the UI and
   * the UI itself both count as reachable; 401 does not.
   */
  @ParameterizedTest(name = "GET {0} is reachable without a token")
  @ValueSource(
      strings = {
        "/swagger-ui",
        "/swagger-ui/",
        "/swagger-ui.html",
        "/swagger-ui/index.html",
        "/v3/api-docs",
        "/v3/api-docs/swagger-config"
      })
  void documentationIsPublic(String path) throws Exception {
    int status = statusOf("GET", path);

    assertThat(status).as("GET %s answered %d", path, status).isBetween(200, 399);
  }

  @ParameterizedTest(name = "{0} leads to the UI rather than to an error")
  @ValueSource(strings = {"/swagger-ui", "/swagger-ui/", "/swagger-ui.html"})
  void everySpellingReachesTheUi(String path) throws Exception {
    // The trailing slash and springdoc's former default are what a person types and what an old
    // bookmark holds. Neither had a mapping, and an unmapped path under a permitted prefix used to
    // surface as 401 rather than 404 — twice as confusing as either would have been alone.
    HttpResponse<String> response = send("GET", path);

    assertThat(response.statusCode()).as("GET %s", path).isBetween(300, 399);
    assertThat(response.headers().firstValue("Location"))
        .hasValueSatisfying(location -> assertThat(location).contains("/swagger-ui/index.html"));
  }

  @Test
  @DisplayName("the specification carries a link back to the application")
  void descriptionLinksBackToTheApplication() throws Exception {
    // Swagger UI renders the description as Markdown, so this is the whole feature: the UI is a
    // page with no navigation of ours on it.
    HttpResponse<String> spec = send("GET", "/v3/api-docs");

    assertThat(spec.body()).contains("https://maintenance.smartsupply.com.de/");
  }

  /**
   * The regression this pull request exists for.
   *
   * <p>HEAD is how every {@code curl -I}, uptime probe and link checker asks whether a URL is
   * there. It answered 401 on the API documentation and on the health endpoint alike, which would
   * have shown a monitoring system a permanently unauthorised health check.
   */
  @ParameterizedTest(name = "HEAD {0} is reachable without a token")
  @ValueSource(strings = {"/swagger-ui", "/swagger-ui/index.html", "/v3/api-docs", "/api/health"})
  void headIsPublicToo(String path) throws Exception {
    int status = statusOf("HEAD", path);

    assertThat(status)
        .as("HEAD %s answered %d — a browser is served the same path fine", path, status)
        .isBetween(200, 399);
  }

  @Test
  @DisplayName("the query endpoint still refuses an unauthenticated caller")
  void queryStaysGuarded() throws Exception {
    // The contrast that makes the permits above safe: opening the documentation opened nothing
    // else. This is the endpoint that costs a provider call.
    HttpResponse<String> response = send("POST", "/api/query");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  @DisplayName("a path outside the permit list stays unauthorised, for GET and HEAD alike")
  void unlistedPathIsUnauthorised() throws Exception {
    // Authorization runs before dispatch, so an anonymous caller learns nothing about which paths
    // exist. That is the design; a 401 on a *documented* path is the defect. Widening the permit
    // list to HEAD must not have widened it to everything.
    assertThat(statusOf("GET", "/api/protocols/mine")).isEqualTo(401);
    assertThat(statusOf("HEAD", "/api/protocols/mine")).isEqualTo(401);
  }

  private int statusOf(String method, String path) throws IOException, InterruptedException {
    return send(method, path).statusCode();
  }

  private HttpResponse<String> send(String method, String path)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + path))
        .method(method, BodyPublishers.noBody())
        .timeout(Duration.ofSeconds(10))
        .build();

    return client.send(request, BodyHandlers.ofString());
  }
}
