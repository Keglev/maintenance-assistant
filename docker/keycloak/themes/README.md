<!--
  DEPLOY RUNBOOK — the login theme is a HAND-DEPLOYED ARTIFACT.
  CI ships application images only. It never syncs compose files or anything they mount, so this
  directory reaches the server the same way the Caddyfile does: by hand (PROJECT-PHASES, OPS RULES).

    1. On the server, in /opt/maintenance-assistant:  git pull        (the theme directory arrives)
    2. cp docker-compose.prod.yml docker-compose.prod.yml.bak-$(date +%Y%m%d-%H%M%S)
       then apply the merged keycloak block — COPY OVER the file, never `mv` (OPS RULE 3)
    3. docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --force-recreate keycloak
       (an env or mount change is inert until the container is recreated — OPS RULE 2)
    4. ONE-TIME, in the admin console: Realm settings -> Themes -> Login theme -> wartungsassistent
       The realm already exists, so `--import-realm` does not apply the loginTheme from the export.
    5. Verify in a PRIVATE window: the login page, both OS colour schemes, DE and EN.
       A broken theme falls back to stock SILENTLY, so also check the log:
         docker compose -f docker-compose.prod.yml logs keycloak | grep -i -e freemarker -e theme
-->

# Keycloak themes

One theme lives here: **`wartungsassistent`**, a login theme for Keycloak **26.7**
(image `quay.io/keycloak/keycloak:26.7`, built-in themes from `org.keycloak.keycloak-themes-26.7.1.jar`).

It exists because the login page was the last surface still wearing stock Keycloak: the demo flow
took a visitor from a designed landing page to a foreign-looking one, mid-login.

## What it overrides, and what it does not

| File | Why |
|---|---|
| `theme.properties` | Declares `parent=keycloak.v2`, appends one stylesheet, and reads two values from the environment. |
| `resources/css/wartungsassistent.css` | The skin. Loaded **after** the parent's `css/styles.css`. |
| `messages/messages_{de,en}.properties` | Only the keys this theme adds. Every other string stays Keycloak's, in every language Keycloak ships. |
| `footer.ftl` | **The one template override.** Keycloak ships it as an empty macro whose own comment names it the extension point for a custom footer, so there is no upstream code copied here to fall behind on the next upgrade. |

Nothing else is overridden. `login.ftl`, `template.ftl` and the field macros stay the parent's:
every overridden template is a file that has to be re-checked against each Keycloak release.

## Colour scheme

The login page **follows the operating system, and only the operating system**, through the
dark-mode switch the parent already implements (`kcDarkModeClass`, toggled from
`prefers-color-scheme` by a script in the parent's `template.ftl`). It cannot read the app's manual
light/dark choice — that lives in the app's own origin — and it deliberately does not try: an auth
URL is not a transport for interface state. A user who forced light in the app on a dark OS sees a
dark login page. Accepted, and it matches what the app itself does before anyone presses the toggle.

## The token values are copies

Every colour in `wartungsassistent.css` is copied from **`frontend/src/styles.css`, which is the
source of truth**. Keycloak serves this file from its own origin and classpath and cannot import
the app's stylesheet — a cross-origin stylesheet on the login page is exactly what the CSP refuses.
Changing a colour in the app means changing it here too.

## Configuration

| Variable | Effect |
|---|---|
| `WA_HOME_URL` | Target of the "back to home" link. **Empty ⇒ no link is rendered** — a back-link to the wrong environment is worse than none. |
| `WA_SHOW_DEMO_ACCOUNTS` | `true` shows the demo-account hint box. Off by default: published credentials are a property of a public demo, not of a theme. |

Both are read by `theme.properties` through `${env.…}` and set per environment in the compose files.

## Working on it locally

`docker compose up -d keycloak`. The dev stack runs `start-dev`, which **disables theme caching**,
so an edit is live on reload — no flag and no restart needed. Production runs `start` with caching
on, which is why step 3 above force-recreates the container.
