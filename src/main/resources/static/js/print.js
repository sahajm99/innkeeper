/* The print button on the invoice. The page already prints correctly from the browser menu, so
   all this does is save the visitor a trip to it. */
(function () {
  "use strict";

  document.addEventListener("click", function (event) {
    var button = event.target.closest("[data-print]");
    if (!button) {
      return;
    }
    event.preventDefault();
    window.print();
  });
})();
