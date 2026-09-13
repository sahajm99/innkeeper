/* The availability register.
   The cells are drawn by the server and each one already submits a one-night stay, so the page
   works with this file blocked. What it adds is picking a range: arrow keys move, Enter or Space
   sets the check-in and then the check-out, and the running total is announced as it changes. */
(function () {
  "use strict";

  var strip = document.querySelector(".strip");
  if (!strip) {
    return;
  }

  var cells = Array.prototype.slice.call(strip.querySelectorAll(".night"));
  if (!cells.length) {
    return;
  }

  var status = document.getElementById("strip-status");
  var checkInField = document.getElementById("strip-check-in");
  var checkOutField = document.getElementById("strip-check-out");
  var bookButton = document.getElementById("strip-book");
  var rate = Number(strip.getAttribute("data-rate") || "0");
  var MOVES = ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Home", "End"];

  var start = checkInField && checkInField.value ? checkInField.value : null;
  var end = checkOutField && checkOutField.value ? checkOutField.value : null;

  function isFree(cell) {
    return cell.getAttribute("data-available") === "true";
  }

  function dateOf(cell) {
    return cell.getAttribute("data-date");
  }

  function indexOfDate(value) {
    for (var i = 0; i < cells.length; i++) {
      if (dateOf(cells[i]) === value) {
        return i;
      }
    }
    return -1;
  }

  function money(amount) {
    return "$" + amount.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  }

  function nightsBetween() {
    if (!start || !end) {
      return 0;
    }
    var from = indexOfDate(start);
    var to = indexOfDate(end);
    if (from < 0) {
      return 0;
    }
    return (to < 0 ? cells.length : to) - from;
  }

  function announce(sentence) {
    if (status) {
      status.textContent = sentence;
    }
  }

  function paint() {
    var from = start ? indexOfDate(start) : -1;
    var to = end ? indexOfDate(end) : -1;
    cells.forEach(function (cell, index) {
      var picked = index === from || (to >= 0 && index === to);
      var inside = from >= 0 && to > from && index > from && index < to;
      cell.setAttribute("aria-pressed", picked ? "true" : "false");
      if (inside) {
        cell.setAttribute("data-in-range", "true");
      } else {
        cell.removeAttribute("data-in-range");
      }
    });
    if (checkInField) {
      checkInField.value = start || "";
    }
    if (checkOutField) {
      checkOutField.value = end || "";
    }
    if (bookButton) {
      bookButton.disabled = !(start && end);
    }
  }

  function describe() {
    var nights = nightsBetween();
    if (!start) {
      announce("Pick your first night.");
      return;
    }
    if (!end) {
      announce("Checking in " + labelOf(start) + ". Pick the morning you leave.");
      return;
    }
    announce(
      (nights === 1 ? "1 night, " : nights + " nights, ") + money(nights * rate) + " before tax."
    );
  }

  function labelOf(value) {
    var index = indexOfDate(value);
    if (index < 0) {
      return value;
    }
    var label = cells[index].getAttribute("aria-label") || value;
    return label.split(",")[0];
  }

  function rangeIsFree(fromIndex, toIndex) {
    for (var i = fromIndex; i < toIndex; i++) {
      if (!isFree(cells[i])) {
        return false;
      }
    }
    return true;
  }

  function choose(cell) {
    if (!isFree(cell)) {
      announce("That night is already booked.");
      return;
    }
    var value = dateOf(cell);
    if (!start || end || indexOfDate(value) <= indexOfDate(start)) {
      start = value;
      end = null;
      paint();
      describe();
      return;
    }
    if (!rangeIsFree(indexOfDate(start), indexOfDate(value))) {
      announce("Those dates include a booked night.");
      return;
    }
    end = value;
    paint();
    describe();
  }

  /* The register as rows of seven, padding included, so moving up or down keeps the weekday
     column even though the first week starts part-way through. */
  var grid = Array.prototype.slice.call(strip.querySelectorAll(".strip-week")).map(function (row) {
    return Array.prototype.slice.call(row.children).map(function (child) {
      return child.classList && child.classList.contains("night") ? child : null;
    });
  });

  function positionOf(cell) {
    for (var row = 0; row < grid.length; row++) {
      var column = grid[row].indexOf(cell);
      if (column >= 0) {
        return { row: row, column: column };
      }
    }
    return null;
  }

  function focusCell(cell) {
    if (!cell) {
      return;
    }
    cells.forEach(function (other) {
      other.setAttribute("tabindex", "-1");
    });
    cell.setAttribute("tabindex", "0");
    cell.focus();
  }

  function neighbour(cell, key) {
    var at = positionOf(cell);
    if (!at) {
      return null;
    }
    var index = cells.indexOf(cell);
    if (key === "ArrowRight") {
      return cells[Math.min(cells.length - 1, index + 1)];
    }
    if (key === "ArrowLeft") {
      return cells[Math.max(0, index - 1)];
    }
    if (key === "ArrowDown" || key === "ArrowUp") {
      var row = grid[at.row + (key === "ArrowDown" ? 1 : -1)];
      return row && row[at.column] ? row[at.column] : cell;
    }
    var week = grid[at.row].filter(Boolean);
    return key === "Home" ? week[0] : week[week.length - 1];
  }

  strip.addEventListener("click", function (event) {
    var cell = event.target.closest(".night");
    if (!cell) {
      return;
    }
    event.preventDefault();
    choose(cell);
  });

  strip.addEventListener("keydown", function (event) {
    var cell = event.target.closest(".night");
    if (!cell) {
      return;
    }
    if (MOVES.indexOf(event.key) >= 0) {
      event.preventDefault();
      focusCell(neighbour(cell, event.key));
      return;
    }
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      choose(cell);
    }
  });

  var first = start ? indexOfDate(start) : 0;
  cells.forEach(function (cell) {
    cell.setAttribute("tabindex", "-1");
  });
  cells[first < 0 ? 0 : first].setAttribute("tabindex", "0");
  paint();
  describe();
})();
