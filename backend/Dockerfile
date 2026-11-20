# =============================================================================
# backend/Dockerfile - Multi-stage build of the backend image (Spring Boot jar on
# a Temurin JRE).
# Usage: `docker build backend` locally; in CI, backend-ci builds it for the scan
# and deploy-tier.yml builds, scans and pushes it, passing GIT_COMMIT and the
# runner's Maven repository as the "m2" build context.
# Produces: an image whose /api/health reports the commit it was built from.
# FAILURE MODE: the build fails on a dependency that cannot be resolved or a
# compile error; tests do not run here (CI runs them). Both base images exist for
# amd64 and arm64, so a laptop of either kind builds it; CI publishes amd64 only
# (production is x86_64, docs/arc42/07-deployment-view.md; deploy-tier.yml says why).
# =============================================================================

# --- seed for the local Maven repository ------------------------------------
# Empty by default. CI replaces it with the runner's cached ~/.m2/repository
# (build context "m2"), so dependency:go-offline downloads only what that cache
# lacks instead of everything from Maven Central. A plain local build is unchanged.
FROM scratch AS m2

# --- build ------------------------------------------------------------------
# The build JDK stays on the runtime's Temurin 21 LTS line; .github/dependabot.yml
# says why Dependabot does not propose this image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY --from=m2 / /root/.m2/repository/

# Dependencies resolve in their own layer, so editing sources does not re-download
# the world on every image build.
COPY pom.xml ./
# .mvn carries maven.config and the settings it names, so the image build resolves
# through the same mirror as every other build.
COPY .mvn ./.mvn
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# The commit this image is built from, reported by /api/health so a deploy can
# verify WHICH build is live rather than only that one is up. deploy-tier.yml
# passes it; a local build without it reads "local" — never a stale sha.
ARG GIT_COMMIT=local
# Tests run in CI against the full stack; the image build only produces the artifact.
RUN mvn -B -ntp clean package -DskipTests -Dgit.commit="$GIT_COMMIT"

# --- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime
WORKDIR /app

# Unprivileged user: nothing in this container needs root.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --home /app app \
 && mkdir -p /var/lib/maintenance/files \
 && chown -R app:app /app /var/lib/maintenance

COPY --from=build --chown=app:app /build/target/*.jar /app/application.jar

USER app
EXPOSE 8080

# Container-aware heap sizing; the VPS runs five containers in ~8 GB (NFR-5) —
# caddy, frontend, backend, keycloak and postgres, per ops/docker-compose.prod.yml.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
