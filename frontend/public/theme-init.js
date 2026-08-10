/*
 * Puts the right data-theme on <html> before the first paint.
 *
 * WHY THIS IS A FILE AND NOT AN INLINE <script>. The usual anti-flash trick is a few lines inlined
 * in <head>, and it is exactly what the production CSP forbids: script-src is 'self' with no
 * 'unsafe-inline' and no nonce (a statically served bundle behind Caddy cannot produce a per-request
 * nonce — see DECISIONS.txt, browser security). A same-origin file satisfies 'self' and costs one
 * request that is already warm in the cache after the first visit. Weakening the CSP to save it
 * would trade a real protection for a cosmetic one.
 *
 * It runs render-blocking in <head>, deliberately: after the paint it would BE the flash it exists
 * to prevent. It is duplicated logic with ThemeService by necessity — nothing of Angular exists
 * yet — so the storage key and the two values are the contract between them.
 */
(function () {
  try {
    var stored = localStorage.getItem('ma-theme');
    var dark =
      stored === 'dark' ||
      (stored !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  } catch (error) {
    // Storage the browser refuses to hand over, or a browser without matchMedia: the stylesheet's
    // own defaults are the light theme, so doing nothing here is already the right fallback.
  }
})();
