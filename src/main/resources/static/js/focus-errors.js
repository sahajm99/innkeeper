/* When a form comes back with errors, the summary is what the visitor needs to read first, so it
   takes focus. The server already put the summary at the top of the form and gave it
   tabindex="-1"; all this does is move the caret there once, on load. */
(function () {
  "use strict";

  function focusSummary() {
    var summary = document.querySelector(".form-errors[tabindex]");
    if (summary) {
      summary.focus();
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", focusSummary);
  } else {
    focusSummary();
  }
})();
