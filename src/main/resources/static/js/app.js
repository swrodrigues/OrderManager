// Small progressive enhancements. No inline scripts anywhere (strict CSP).
(function () {
  "use strict";

  // New order form: add/remove line rows, keeping field names as lines[0].skuCode, lines[0].qty, ...
  function reindexLines(tbody) {
    tbody.querySelectorAll("tr.line-row").forEach(function (row, i) {
      row.querySelectorAll("[data-line-field]").forEach(function (field) {
        var name = "lines[" + i + "]." + field.getAttribute("data-line-field");
        field.name = name;
        field.id = name.replace(/[\[\].]/g, "");
      });
    });
  }

  document.addEventListener("click", function (e) {
    var add = e.target.closest("[data-add-line]");
    if (add) {
      var tbody = document.getElementById("lines");
      var template = document.getElementById("line-template");
      tbody.appendChild(template.content.cloneNode(true));
      reindexLines(tbody);
      return;
    }
    var remove = e.target.closest("[data-remove-line]");
    if (remove) {
      var body = remove.closest("tbody");
      if (body.querySelectorAll("tr.line-row").length > 1) {
        remove.closest("tr").remove();
        reindexLines(body);
      }
    }
  });
})();
