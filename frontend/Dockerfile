# Multi-stage build for the maintenance-assistant frontend.
# Both base images are published for linux/amd64 and linux/arm64, so the same file
# builds on a laptop of either architecture. The image CI publishes is amd64 only:
# production is a Hetzner CX33 (x86_64) — see docs/arc42/07-deployment-view.md —
# and frontend-deploy.yml's build-push step records why the arm64 leg was dropped.

# --- build ------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing a component does not
# reinstall node_modules on every image build.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Tests run in CI with a real browser; the image build only produces the bundle.
RUN npm run build

# The commit this image was built from, served as /build-info.json so a deploy can
# check which build answers (frontend-deploy.yml sets it; a local build says
# "local"). Declared after the bundle build so a new commit does not invalidate the
# cached npm layers above.
ARG GIT_COMMIT=local
RUN printf '{"commit":"%s"}\n' "$GIT_COMMIT" \
      > dist/maintenance-assistant-frontend/browser/build-info.json

# --- runtime ----------------------------------------------------------------
# The unprivileged variant runs as uid 101 and listens on 8080 out of the box. The
# stock nginx image cannot simply be given a USER: its master process needs to create
# /var/cache/nginx at startup and fails with EACCES as a non-root user.
# The stable line (even minor), not mainline: it keeps receiving rebuilds with Alpine
# security fixes without new nginx features. A superseded line stops being rebuilt and
# fails the image scan (image-scan.sh); bump the minor deliberately when it does.
FROM nginxinc/nginx-unprivileged:1.30-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/maintenance-assistant-frontend/browser /usr/share/nginx/html

EXPOSE 8080

# 127.0.0.1, not localhost: nginx.conf listens on IPv4 only, while `localhost`
# resolves to ::1 first inside the container and the check would report a
# perfectly healthy container as unhealthy.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget --quiet --spider http://127.0.0.1:8080/ || exit 1
