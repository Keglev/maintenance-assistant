# Multi-stage build for the maintenance-assistant frontend.
# Both base images are published for linux/amd64 and linux/arm64, so the same file
# builds for a laptop and for the Hetzner CAX (arm64) target host.

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

# --- runtime ----------------------------------------------------------------
# The unprivileged variant runs as uid 101 and listens on 8080 out of the box. The
# stock nginx image cannot simply be given a USER: its master process needs to create
# /var/cache/nginx at startup and fails with EACCES as a non-root user.
FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/maintenance-assistant-frontend/browser /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget --quiet --spider http://localhost:8080/ || exit 1
