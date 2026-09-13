/* Theme, applied before the first paint.
   Loaded in <head> without defer so the attribute is on <html> before anything is drawn; a page
   that flashes white and then goes dark is worse than no toggle at all. The content security
   policy forbids inline scripts, so this is a file rather than four lines in the template. */
(function () {
  "use strict";

  var KEY = "theme";
  var root = document.documentElement;

  function stored() {
    try {
      var value = window.localStorage.getItem(KEY);
      return value === "dark" || value === "light" ? value : null;
    } catch (error) {
      return null;
    }
  }

  function systemTheme() {
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches
      ? "dark"
      : "light";
  }

  function apply(theme) {
    root.setAttribute("data-theme", theme);
  }

  var chosen = stored();
  if (chosen) {
    apply(chosen);
  }

  function current() {
    return root.getAttribute("data-theme") || systemTheme();
  }

  function label(theme) {
    return theme === "dark" ? "Light theme" : "Dark theme";
  }

  function wire() {
    var toggle = document.getElementById("theme-toggle");
    if (!toggle) {
      return;
    }
    var update = function () {
      var dark = current() === "dark";
      toggle.textContent = label(dark ? "dark" : "light");
      toggle.setAttribute("aria-pressed", dark ? "true" : "false");
    };
    update();
    toggle.addEventListener("click", function () {
      var next = current() === "dark" ? "light" : "dark";
      apply(next);
      try {
        window.localStorage.setItem(KEY, next);
      } catch (error) {
        /* private browsing: the choice lasts for this page only, which is still better */
      }
      update();
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", wire);
  } else {
    wire();
  }
})();
