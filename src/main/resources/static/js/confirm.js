/* One question before a form that cannot be undone. The form posts on its own without this file,
   which is why the sentence is written on the form rather than built here: the markup says what
   is about to happen either way, and this only stops to ask. */
(function () {
  "use strict";

  document.addEventListener("submit", function (event) {
    var form = event.target;
    if (!form || !form.getAttribute) {
      return;
    }
    var question = form.getAttribute("data-confirm");
    if (question && !window.confirm(question)) {
      event.preventDefault();
    }
  });
})();
