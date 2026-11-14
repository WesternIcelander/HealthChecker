// HealthChecker dashboard: progressive enhancement only; every page works without this script.
(function () {
  "use strict";

  var tooltip = null;

  // Replaces the native title tooltip of elements with [title][data-tip] by one that also
  // shows on keyboard focus and stays inside the viewport.
  function initTooltips() {
    var targets = document.querySelectorAll(".seg[title], .check[title]");
    if (!targets.length) return;
    tooltip = document.createElement("div");
    tooltip.className = "tooltip";
    tooltip.setAttribute("role", "tooltip");
    tooltip.hidden = true;
    document.body.appendChild(tooltip);
    for (var i = 0; i < targets.length; i++) {
      var el = targets[i];
      el.setAttribute("data-tip", el.getAttribute("title"));
      el.removeAttribute("title");
      el.addEventListener("mouseenter", show);
      el.addEventListener("focus", show);
      el.addEventListener("mouseleave", hide);
      el.addEventListener("blur", hide);
    }
    window.addEventListener("scroll", hide, { passive: true });
  }

  function show(event) {
    var el = event.currentTarget;
    tooltip.textContent = el.getAttribute("data-tip");
    tooltip.hidden = false;
    var rect = el.getBoundingClientRect();
    var tip = tooltip.getBoundingClientRect();
    var margin = 8;
    var left = rect.left + rect.width / 2 - tip.width / 2;
    left = Math.max(margin, Math.min(left, document.documentElement.clientWidth - tip.width - margin));
    var top = rect.top - tip.height - 6;
    if (top < margin) top = rect.bottom + 6;
    tooltip.style.left = (left + window.scrollX) + "px";
    tooltip.style.top = (top + window.scrollY) + "px";
  }

  function hide() {
    if (tooltip) tooltip.hidden = true;
  }

  function init() {
    document.documentElement.classList.add("js");
    initTooltips();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
