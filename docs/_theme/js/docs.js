// =============================================================================
// maintenance-assistant Docs — Runtime
// Theme toggle, mobile nav drawer, active-link marking, and Mermaid init.
// Lives outside the Pandoc template so regex/`$` characters never collide with
// Pandoc's variable syntax during the build.
// =============================================================================

// Guarded: the landing pages load docs.js but no Mermaid, so the global is absent.
if (typeof mermaid !== "undefined") {
  mermaid.initialize({ startOnLoad: true });
}

// Theme toggle flips the attribute tokens.css keys off and persists the choice.
var toggle = document.querySelector(".theme-toggle");
if (toggle) {
  toggle.addEventListener("click", function () {
    var next = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try { localStorage.setItem("maintenance-assistant-theme", next); } catch (e) {}
  });
}

var menu = document.querySelector(".menu-btn");
if (menu) {
  menu.addEventListener("click", function () {
    document.querySelector(".layout").classList.toggle("nav-open");
  });
}

// Mark the current page in the sidebar at runtime, avoiding per-page build logic.
// The arc42 and ADR entries are grouped into collapsed <details> ranges, so the
// group holding the current page is opened here too — otherwise an ADR page would
// show shut drawers and no sign of where the reader is. Pages outside every group
// open nothing: collapsed is the default the markup ships. <summary> is natively
// keyboard-operable, so no handler is bound.
(function () {
  var here = location.pathname.replace(/index\.html$/, "");
  document.querySelectorAll(".sidebar a").forEach(function (a) {
    if (a.getAttribute("href").replace(/index\.html$/, "") === here) {
      a.setAttribute("aria-current", "page");
      var group = a.closest && a.closest("details");
      if (group) {
        group.open = true;
      }
    }
  });
})();

// Language switch: point EN/DE at the current page's translated twin when one
// exists. Only the landing and the overview are translated, so any other page
// shows DE disabled rather than routing the reader somewhere unrelated. Done at
// runtime to avoid wiring a per-page twin URL through the build.
(function () {
  var links = document.querySelectorAll(".lang-switch a");
  if (links.length < 2) return;
  var en = links[0], de = links[1];
  var base = "/maintenance-assistant";
  var path = location.pathname;
  var file = path.substring(path.lastIndexOf("/") + 1);
  var dir = path.substring(0, path.lastIndexOf("/") + 1);

  function current(active) {
    en.removeAttribute("aria-current");
    de.removeAttribute("aria-current");
    active.setAttribute("aria-current", "true");
  }

  // The site landing is base/ or base/index.html specifically — a deep section
  // index.html (e.g. adr/index.html) must not be mistaken for it.
  if (path === base + "/" || path === base + "/index.html") {
    en.href = base + "/"; de.href = base + "/index-de.html"; current(en);
  } else if (path === base + "/index-de.html") {
    en.href = base + "/"; de.href = base + "/index-de.html"; current(de);
  } else if (file === "overview.html") {
    en.href = path; de.href = dir + "overview-de.html"; current(en);
  } else if (file === "overview-de.html") {
    en.href = dir + "overview.html"; de.href = path; current(de);
  } else {
    // English-only page: there is no German twin, so EN stays active and DE is
    // shown disabled (greyed, not clickable) rather than sending the reader off
    // to an unrelated page.
    en.href = path;
    de.removeAttribute("href");
    de.setAttribute("aria-disabled", "true");
    de.setAttribute("title", "Diese Seite ist nur auf Englisch verfügbar");
    current(en);
  }
})();
