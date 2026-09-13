/* Copy buttons. A confirmation code is the one string a guest has to keep, so the page offers to
   put it on the clipboard and says so for two seconds. Without this file the code is still there
   to select by hand, which is why the button is added to markup that already works. */
(function () {
  "use strict";

  var RESTORE_AFTER_MS = 2000;

  function textOf(button) {
    var target = document.querySelector(button.getAttribute("data-copy"));
    return target ? target.textContent.trim() : "";
  }

  function fallbackCopy(text) {
    var holder = document.createElement("textarea");
    holder.value = text;
    holder.setAttribute("readonly", "readonly");
    holder.className = "honeypot";
    document.body.appendChild(holder);
    holder.select();
    try {
      document.execCommand("copy");
    } catch (error) {
      /* nothing to do: the code is on the page to select by hand */
    }
    document.body.removeChild(holder);
  }

  function confirmCopied(button) {
    if (button.dataset.busy === "true") {
      return;
    }
    var original = button.textContent;
    button.dataset.busy = "true";
    button.textContent = "Copied";
    window.setTimeout(function () {
      button.textContent = original;
      button.dataset.busy = "false";
    }, RESTORE_AFTER_MS);
  }

  document.addEventListener("click", function (event) {
    var button = event.target.closest("[data-copy]");
    if (!button) {
      return;
    }
    event.preventDefault();
    var text = textOf(button);
    if (!text) {
      return;
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () {
          confirmCopied(button);
        },
        function () {
          fallbackCopy(text);
          confirmCopied(button);
        }
      );
      return;
    }
    fallbackCopy(text);
    confirmCopied(button);
  });
})();
