# Multi-stage build for the maintenance-assistant backend.
# Both base images are published for linux/amd64 and linux/arm64, so the same file
# builds on a laptop of either architecture. The image CI publishes is amd64 only:
# production is a Hetzner CX33 (x86_64) — see docs/arc42/07-deployment-view.md —
# and backend-deploy.yml's build-push step records why the arm64 leg was dropped.

# --- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing sources does not re-download
# the world on every image build.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
# Tests run in CI against the full stack; the image build only produces the artifact.
RUN mvn -B -ntp clean package -DskipTests

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
# caddy, frontend, backend, keycloak and postgres, per docker/docker-compose.prod.yml.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
