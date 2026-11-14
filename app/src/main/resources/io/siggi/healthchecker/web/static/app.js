// HealthChecker dashboard: progressive enhancement only; every page works without this script.
(function () {
  "use strict";

  function init() {
    document.documentElement.classList.add("js");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
