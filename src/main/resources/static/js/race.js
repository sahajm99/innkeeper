/* The race button.
   Ten bookings for one room on one night, sent at the same instant. Exactly one survives, and the
   sentence this writes says so in plain words, because the point of the demo is that the database
   and not the application is what makes it true. */
(function () {
  "use strict";

  var DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  var MONTHS = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
  ];

  var button = document.getElementById("race-button");
  var result = document.getElementById("race-result");
  var detail = document.getElementById("race-detail");
  if (!button || !result) {
    return;
  }

  /* "2027-04-03" as "Fri 3 Apr 2027", built by hand so no locale can reorder it. */
  function longDate(iso) {
    var parts = String(iso).split("-");
    if (parts.length !== 3) {
      return iso;
    }
    var when = new Date(Date.UTC(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2])));
    return (
      DAYS[when.getUTCDay()] +
      " " +
      when.getUTCDate() +
      " " +
      MONTHS[when.getUTCMonth()] +
      " " +
      when.getUTCFullYear()
    );
  }

  function sentence(race) {
    return (
      race.attempts +
      " visitors tried to book " +
      race.room +
      " for " +
      longDate(race.night) +
      " at the same instant: " +
      (race.created === 1 ? "1 booking created" : race.created + " bookings created") +
      ", " +
      race.rejected +
      " rejected by the database."
    );
  }

  button.addEventListener("click", function () {
    button.disabled = true;
    result.textContent = "Running ten bookings at once.";
    if (detail) {
      detail.textContent = "";
    }
    fetch("/api/demo/race", {
      method: "POST",
      headers: { Accept: "application/json" }
    })
      .then(function (response) {
        if (!response.ok) {
          throw new Error(String(response.status));
        }
        return response.json();
      })
      .then(function (race) {
        result.textContent = sentence(race);
        if (detail) {
          detail.textContent =
            "The winner was " + race.winnerCode + ", cancelled again straight away. " +
            "The whole thing took " + race.durationMs + " ms.";
        }
      })
      .catch(function (error) {
        result.textContent =
          error.message === "429"
            ? "The race has been run too many times from this address in the last hour."
            : "The race did not finish. Try again in a moment.";
      })
      .then(function () {
        button.disabled = false;
      });
  });
})();
