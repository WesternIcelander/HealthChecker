// HealthChecker dashboard: progressive enhancement only; every page works without this script.
(function () {
  "use strict";

  var tooltip = null;

  // Replaces the native title tooltip of elements with [title][data-tip] by one that also
  // shows on keyboard focus and stays inside the viewport.
  function initTooltips() {
    var targets = document.querySelectorAll(".seg[title], .check[title], .cal-day[title]");
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

  // ---- day popover (calendar view) ----
  // Each calendar day links to its day page; with JS, a click fetches that page and shows its
  // .day-detail section in a popover next to the day. A server-rendered ?day= panel is moved
  // into the popover on load.

  var popover = null;
  var popoverBody = null;
  var popoverAnchor = null;
  var requestSeq = 0;

  function initDayPopover() {
    var calendar = document.querySelector(".calendar");
    if (!calendar) return;
    popover = document.createElement("div");
    popover.className = "popover";
    popover.setAttribute("role", "dialog");
    popover.hidden = true;
    var close = document.createElement("button");
    close.type = "button";
    close.className = "popover-close";
    close.setAttribute("aria-label", "Close");
    close.textContent = "\u00d7";
    close.addEventListener("click", function () { closePopover(true); });
    popoverBody = document.createElement("div");
    popover.appendChild(close);
    popover.appendChild(popoverBody);
    document.body.appendChild(popover);

    calendar.addEventListener("click", function (event) {
      var link = event.target.closest ? event.target.closest("a.cal-day") : null;
      if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      if (popoverAnchor === link && !popover.hidden) {
        closePopover(true);
      } else {
        openDay(link);
      }
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && !popover.hidden) closePopover(true);
    });
    document.addEventListener("click", function (event) {
      if (popover.hidden || popover.contains(event.target)) return;
      if (event.target.closest && event.target.closest("a.cal-day")) return;
      closePopover(false);
    });
    window.addEventListener("resize", function () { if (!popover.hidden) position(); });

    var panel = document.querySelector(".day-panel");
    var detail = panel && panel.querySelector(".day-detail");
    if (detail) {
      var anchor = calendar.querySelector("a.cal-day[data-date=\"" + detail.getAttribute("data-date") + "\"]");
      if (anchor) {
        panel.hidden = true;
        showPopover(anchor, detail, false);
      }
    }
  }

  function openDay(link) {
    var seq = ++requestSeq;
    fetch(link.href, { credentials: "same-origin", headers: { "Accept": "text/html" } })
      .then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.text();
      })
      .then(function (text) {
        if (seq !== requestSeq) return;
        var doc = new DOMParser().parseFromString(text, "text/html");
        var detail = doc.querySelector(".day-detail");
        if (!detail) throw new Error("no day detail");
        showPopover(link, document.importNode(detail, true), true);
      })
      .catch(function () {
        if (seq === requestSeq) window.location.href = link.href;
      });
  }

  function showPopover(anchor, detail, focus) {
    hide();
    var previous = document.querySelector(".cal-day.cal-open");
    if (previous) previous.classList.remove("cal-open");
    popoverBody.textContent = "";
    popoverBody.appendChild(detail);
    var title = detail.querySelector(".day-title");
    if (title && title.id) popover.setAttribute("aria-labelledby", title.id);
    popoverAnchor = anchor;
    anchor.classList.add("cal-open");
    anchor.setAttribute("aria-expanded", "true");
    popover.hidden = false;
    position();
    if (focus) {
      popover.setAttribute("tabindex", "-1");
      popover.focus({ preventScroll: true });
    }
  }

  function closePopover(returnFocus) {
    requestSeq++;
    popover.hidden = true;
    popoverBody.textContent = "";
    if (popoverAnchor) {
      popoverAnchor.classList.remove("cal-open");
      popoverAnchor.removeAttribute("aria-expanded");
      if (returnFocus) popoverAnchor.focus();
    }
    popoverAnchor = null;
  }

  function position() {
    var rect = popoverAnchor.getBoundingClientRect();
    var box = popover.getBoundingClientRect();
    var margin = 8;
    var left = rect.left + rect.width / 2 - box.width / 2;
    left = Math.max(margin, Math.min(left, document.documentElement.clientWidth - box.width - margin));
    var top = rect.bottom + 6;
    if (top + box.height > window.innerHeight - margin && rect.top - box.height - 6 >= margin) {
      top = rect.top - box.height - 6;
    }
    popover.style.left = (left + window.scrollX) + "px";
    popover.style.top = (top + window.scrollY) + "px";
  }

  function init() {
    document.documentElement.classList.add("js");
    initTooltips();
    initDayPopover();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
