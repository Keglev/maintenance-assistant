/*
 * Puts the right data-theme and data-font on <html> before the first paint.
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
    // Two states plus unset. Anything that is not exactly 'light' or 'dark' — including the literal
    // 'system' the old three-state control used to write — means "no choice", and the operating
    // system decides.
    var theme = localStorage.getItem('ma-theme');
    var dark =
      theme === 'dark' ||
      (theme !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');

    // Font scale, applied here for the same reason as the theme: set after the first paint it would
    // reflow the whole page in front of the reader.
    var font = localStorage.getItem('ma-font');
    if (font === 'lg' || font === 'xl') {
      document.documentElement.setAttribute('data-font', font);
    }
  } catch (error) {
    // Storage the browser refuses to hand over, or a browser without matchMedia: the stylesheet's
    // own defaults are the light theme at normal size, so doing nothing here is the right fallback.
  }
})();
