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

  A THEME-ONLY CHANGE (no compose edit) is steps 1 + 3 + 5. The theme directory is a DIRECTORY
  mount, so new and changed files arrive with the pull and need no compose change — but production
  runs `start` with theme caching ON, so the files are not live until the container is recreated.
  Step 4 is one-time and does not repeat: the realm already points at the theme.
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

### Verification, exactly

Measured locally on Keycloak 26.7, so a production drill has an expectation rather than a guess:
the parent's script registers a `change` listener on `matchMedia("(prefers-color-scheme: dark)")`,
so **the page repaints live, with no reload and no navigation**, in both directions. Switching the
OS scheme with the login page open is enough — if a tester sees a half-changed page, that is a
defect and not the mechanism working as designed.

**Drill in a FRESH private window, with every other private window closed first** — or with
DevTools' "Disable cache" ticked. Keycloak serves theme resources with
`cache-control: max-age=2592000`, thirty days, so a browser that has seen the login page before is
entitled to keep showing you the stylesheet from last time. Found in the #47 production drill, where
the first pass verified the previous theme and looked like a failed deploy. Note that a private
window is not a fresh one if another is already open: they share a session, and therefore its cache.
This never bites locally — `start-dev` serves the same files as `no-cache`, which is exactly why the
trap is invisible right up until a deploy.

## Known pitfalls

**Repainting the canvas makes the parent's elements yours — including the ones you never named.**
This theme sets `background-image: none` on the login backdrop and lights the canvas. Everything
`keycloak.v2` coloured *for* its dark photographic backdrop is then sitting on the wrong ground, and
nothing announces it. It has cost two defects so far, both found only by a human looking at the
rendered page:

| Symptom | What it actually was |
|---|---|
| The realm heading was invisible in the light scheme (measured **1.09:1**, white on `#f3f5f7`) | `#kc-header-wrapper`, an element this stylesheet never mentioned. The parent's own `styles.css` pins it with `color: … !important` |
| The back-link and demo box hugged the card's outer edge | `.pf-v5-c-login__main-footer` rendered outside `__main-body`, which is the one region PatternFly gives **no** padding |

Two rules follow from that, and neither is optional:

1. **A code-level check of this file cannot find this bug class.** The defect is an element that is
   absent from the stylesheet, so reading the stylesheet shows nothing wrong. Only a rendered page
   does.
2. **Re-run the contrast sweep after any theme edit**, in *both* schemes, over everything visible —
   above the card, inside it, in the footer, and on the error paths (a wrong password and a
   standalone error page render different markup). One scheme reading correctly proves nothing about
   the other: the heading was fine in dark purely by accident, for as long as the defect existed.

**A rule for markup the parent never renders is dead, and it looks exactly like a rule that works.**
This theme carried `.pf-v5-c-alert.pf-m-danger, .pf-v5-c-alert.pf-m-error` — a styled error banner —
for as long as it existed. Keycloak 26.7 renders a wrong password as **inline helper text under the
field**, not as an alert, so nothing on the login flow ever matched it. Nothing was broken and
nothing looked broken; the rule simply never applied, which is why it survived a review. Deleted
2026-08-20, together with the three `--wa-danger-*` declarations per palette that had no other
consumer.

The check that catches this class — a rule aimed at markup that is not there, or an element the
parent started rendering that this theme does not name — is **the contrast sweep of a rendered
page**, the same one rule 2 above already requires. It is not a linter and it is not a diff: a
sweep enumerates what the browser actually painted, so an element with no rule of ours shows up as a
measurement (which is how the realm heading was found) and a rule of ours with no element shows up
as a selector that never appears in the sweep's output. If a future Keycloak release starts
rendering alerts on the login flow again, that is the check that will say so — and the fix is to
re-add the rule with a measured pair of colours, not to restore this one from history.

`!important` is used exactly once, on the realm heading, because the parent used it first and
nothing else overrides an `!important` declaration. Redefining the PatternFly variable behind it
(`--pf-v5-global--Color--light-100`) was rejected: it would repaint every element that legitimately
wants a light colour on a dark fill.

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
