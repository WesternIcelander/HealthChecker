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


  // ---- layout editor (main page, ?edit=layout) ----
  // Categories and services can be reordered by drag and drop, or with the up/down buttons and
  // each service's category menu (keyboard and touch). "Save" posts the whole layout as JSON.

  var editor = null;
  var dragged = null;
  var dirty = false;

  function initLayoutEditor() {
    editor = document.querySelector(".layout-editor");
    if (!editor) return;
    editor.classList.add("le-ready");
    var items = editor.querySelectorAll(".le-category, .le-service");
    for (var i = 0; i < items.length; i++) {
      if (items[i].classList.contains("le-category") && editor.classList.contains("le-flat")) continue;
      items[i].setAttribute("draggable", "true");
    }
    editor.addEventListener("click", function (event) {
      var button = event.target.closest ? event.target.closest("button") : null;
      if (!button || !editor.contains(button)) return;
      if (button.classList.contains("le-up") || button.classList.contains("le-down")) {
        var item = button.closest(".le-service") || button.closest(".le-category");
        moveItem(item, button.classList.contains("le-up") ? -1 : 1, button);
      } else if (button.classList.contains("le-save")) {
        save({ categories: collectLayout() });
      } else if (button.classList.contains("le-reset")) {
        if (window.confirm("Reset the layout to the order in the config file? This affects everyone.")) {
          save({ reset: true });
        }
      } else if (button.classList.contains("le-reload")) {
        dirty = false;
        window.location.reload();
      }
    });
    editor.addEventListener("change", function (event) {
      var select = event.target;
      if (!select.classList || !select.classList.contains("le-move")) return;
      var item = select.closest(".le-service");
      var target = editor.querySelector(".le-category[data-category=\"" + select.value + "\"] > .le-services");
      if (!item || !target) return;
      target.appendChild(item);
      changed();
      select.focus();
      announce("Moved to " + categoryName(target) + ".");
    });
    editor.addEventListener("dragstart", onDragStart);
    editor.addEventListener("dragover", onDragOver);
    editor.addEventListener("drop", function (event) { if (dragged) event.preventDefault(); });
    editor.addEventListener("dragend", function () {
      if (dragged) dragged.classList.remove("le-dragging");
      dragged = null;
      changed();
    });
    window.addEventListener("beforeunload", function (event) {
      if (dirty) { event.preventDefault(); event.returnValue = ""; }
    });
    updateControls();
  }

  function onDragStart(event) {
    var target = event.target;
    if (!target.classList || !(target.classList.contains("le-service") || target.classList.contains("le-category"))) return;
    dragged = target;
    dragged.classList.add("le-dragging");
    event.dataTransfer.effectAllowed = "move";
    try { event.dataTransfer.setData("text/plain", target.getAttribute("data-service") || target.getAttribute("data-category")); } catch (e) { /* ignore */ }
  }

  function onDragOver(event) {
    if (!dragged) return;
    var isService = dragged.classList.contains("le-service");
    var over = event.target.closest ? event.target.closest(isService ? ".le-service, .le-services" : ".le-category") : null;
    if (!over || !editor.contains(over)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = "move";
    if (over === dragged || dragged.contains(over)) return;
    if (over.classList.contains("le-services")) {
      if (!over.querySelector(".le-service:not(.le-dragging)")) over.appendChild(dragged);
      return;
    }
    var rect = over.getBoundingClientRect();
    var before = event.clientY < rect.top + rect.height / 2;
    over.parentNode.insertBefore(dragged, before ? over : over.nextSibling);
  }

  function moveItem(item, delta, button) {
    if (!item) return;
    var sibling = delta < 0 ? item.previousElementSibling : item.nextElementSibling;
    if (!sibling) return;
    item.parentNode.insertBefore(item, delta < 0 ? sibling : sibling.nextSibling);
    changed();
    // Keep focus on the same button; if it's now disabled, use the other one.
    var same = item.querySelector(":scope > .le-controls > ." + (delta < 0 ? "le-up" : "le-down")
      + ", :scope > .le-category-head > .le-controls > ." + (delta < 0 ? "le-up" : "le-down"));
    var other = item.querySelector(":scope > .le-controls > ." + (delta < 0 ? "le-down" : "le-up")
      + ", :scope > .le-category-head > .le-controls > ." + (delta < 0 ? "le-down" : "le-up"));
    if (same && !same.disabled) same.focus(); else if (other) other.focus(); else if (button) button.focus();
    var list = Array.prototype.slice.call(item.parentNode.children);
    announce("Moved to position " + (list.indexOf(item) + 1) + " of " + list.length + ".");
  }

  function changed() {
    dirty = true;
    updateControls();
  }

  function updateControls() {
    var lists = editor.querySelectorAll(".le-categories, .le-services");
    for (var i = 0; i < lists.length; i++) {
      var children = lists[i].children;
      for (var j = 0; j < children.length; j++) {
        var controls = children[j].querySelector(":scope > .le-controls, :scope > .le-category-head > .le-controls");
        if (!controls) continue;
        controls.querySelector(".le-up").disabled = j === 0;
        controls.querySelector(".le-down").disabled = j === children.length - 1;
      }
    }
    var services = editor.querySelectorAll(".le-service");
    for (var k = 0; k < services.length; k++) {
      var select = services[k].querySelector("select.le-move");
      var category = services[k].closest(".le-category");
      if (select && category) select.value = category.getAttribute("data-category");
    }
  }

  function categoryName(list) {
    var name = list.closest(".le-category").querySelector(".le-category-name");
    return name ? name.textContent : "";
  }

  function collectLayout() {
    var result = [];
    var categories = editor.querySelectorAll(".le-category");
    for (var i = 0; i < categories.length; i++) {
      var services = [];
      var items = categories[i].querySelectorAll(".le-service");
      for (var j = 0; j < items.length; j++) services.push(items[j].getAttribute("data-service"));
      result.push({ id: categories[i].getAttribute("data-category"), services: services });
    }
    return result;
  }

  function announce(text, isError, withReload) {
    var status = editor.querySelector(".le-status");
    status.textContent = text;
    status.classList.toggle("form-error", !!isError);
    if (withReload) {
      var reload = document.createElement("button");
      reload.type = "button";
      reload.className = "le-reload";
      reload.textContent = "Reload";
      status.appendChild(document.createTextNode(" "));
      status.appendChild(reload);
    }
  }

  function save(body) {
    body.version = parseInt(editor.getAttribute("data-version"), 10);
    var buttons = editor.querySelectorAll(".le-actions button");
    for (var i = 0; i < buttons.length; i++) buttons[i].disabled = true;
    announce("Saving…");
    fetch(editor.getAttribute("data-action"), {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", "X-CSRF-Token": editor.getAttribute("data-csrf") },
      body: JSON.stringify(body)
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (data) {
        if (response.ok) {
          dirty = false;
          window.location.href = editor.getAttribute("data-done");
          return;
        }
        if (response.status === 409) {
          announce("Someone else changed the layout in the meantime. Reload to see their changes (yours will be lost).", true, true);
        } else {
          announce("Saving failed: " + (data.error || ("HTTP " + response.status)), true);
        }
        for (var j = 0; j < buttons.length; j++) buttons[j].disabled = false;
      });
    }).catch(function () {
      announce("Saving failed: the server couldn't be reached.", true);
      for (var j = 0; j < buttons.length; j++) buttons[j].disabled = false;
    });
  }

  // ---- event edits ----
  // Buttons with data-confirm ask before submitting (e.g. deleting a note). After an edit the
  // list view returns to #event-{id} or #note-{id}: open the <details> around it.

  function initEventEdits() {
    document.addEventListener("click", function (event) {
      var button = event.target.closest && event.target.closest("button[data-confirm]");
      if (button && !window.confirm(button.getAttribute("data-confirm"))) event.preventDefault();
    });
    var id = window.location.hash.slice(1);
    var target = id && document.getElementById(decodeURIComponent(id));
    if (!target) return;
    for (var el = target; el; el = el.parentElement) {
      if (el.tagName === "DETAILS" && !el.classList.contains("note-edit")) el.open = true;
    }
    if (target.tagName === "LI" && target.firstElementChild && target.firstElementChild.tagName === "DETAILS") {
      target.firstElementChild.open = true;
    }
    target.scrollIntoView();
  }

  // ---- endpoint editor ----
  // Without JS the form has two steps ("Continue" after choosing the type). With JS, changing the
  // type swaps in that type's settings from its <template> right away.

  function initEndpointEditor() {
    var form = document.querySelector("form.endpoint-form");
    var select = form && form.querySelector("select.ee-type");
    var target = form && form.querySelector(".ee-settings");
    if (!select || !target) return;
    var step = form.querySelector(".ee-type-step");
    if (step) step.hidden = true;
    select.addEventListener("change", function () {
      var templates = document.querySelectorAll("template.ee-settings-template");
      var template = null;
      for (var i = 0; i < templates.length; i++) {
        if (templates[i].getAttribute("data-type") === select.value) template = templates[i];
      }
      target.textContent = "";
      if (template) target.appendChild(template.content.cloneNode(true));
      var save = form.querySelector(".ee-save");
      if (save) save.hidden = !template;
    });
  }

  function init() {
    document.documentElement.classList.add("js");
    initEventEdits();
    initTooltips();
    initDayPopover();
    initLayoutEditor();
    initEndpointEditor();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
