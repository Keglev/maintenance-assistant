# Documentation site theme

Minimal Jekyll configuration for publishing [`docs/`](../) as a static site on GitHub Pages from
the `gh-pages` branch.

| File | Purpose |
|---|---|
| [`_config.yml`](_config.yml) | Site metadata and the remote theme (`just-the-docs`). |
| [`_layouts/default.html`](_layouts/default.html) | Plain fallback layout, used when the remote theme is unavailable. |

## How it is meant to be used

The files live here rather than at `docs/` root so the repository stays free of Jekyll scaffolding
while nothing is published yet. The publishing workflow (Phase 4) copies `_config.yml` and
`_layouts/` next to the markdown, pre-renders the Mermaid diagrams and pushes the result to
`gh-pages`:

```bash
node docs/scripts/generate-diagrams.mjs        # .mmd -> .svg, next to source
cp docs/_themes/_config.yml docs/              # theme config to the site root
cp -r docs/_themes/_layouts docs/              # fallback layout
# then publish docs/ to the gh-pages branch
```

Kept deliberately small: a remote theme, no custom CSS beyond the fallback layout, and no plugins
outside the GitHub Pages whitelist — the content is the point, not the site.
