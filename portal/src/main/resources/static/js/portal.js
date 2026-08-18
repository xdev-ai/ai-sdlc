(function () {
  "use strict";
  var t = function (key) { return window.AISDLC_I18N?.t?.(key) || key; };

  function parseJson(value, fallback) {
    try { return JSON.parse(value || ""); } catch (_) { return fallback; }
  }

  function asNumber(value) {
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function initHtmxCsrf() {
    if (!window.htmx || document.body.dataset.htmxCsrfReady) return;
    document.body.dataset.htmxCsrfReady = "true";
    document.body.addEventListener("htmx:configRequest", function (event) {
      var token = document.querySelector('meta[name="_csrf"]')?.content;
      var header = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
      if (token) event.detail.headers[header] = token;
    });
    document.body.addEventListener("htmx:afterSettle", function (event) { enhance(event.target); });
  }

  function initChart(root) {
    if (!window.echarts) return;
    root.querySelectorAll("[data-quality-metrics]").forEach(function (element) {
      if (element.dataset.chartReady === "true") return;
      var rows = parseJson(element.dataset.qualityMetrics, []);
      if (!Array.isArray(rows) || !rows.length) return;
      element.dataset.chartReady = "true";
      var chronologicallyOrdered = rows.slice().reverse();
      var chart = window.echarts.init(element, null, { renderer: "svg" });
      var fields = [
        ["deployment_frequency", t("Deployment frequency"), "#577363"],
        ["lead_time_hours", t("Lead time (h)"), "#b3914b"],
        ["change_failure_rate", t("Change failure rate"), "#b85c4a"],
        ["pr_review_time_delta_hours", t("Review delta (h)"), "#566b9c"],
        ["rework_rate", t("Rework rate"), "#856a99"],
        ["review_queue_health", t("Queue health"), "#4b8a7a"],
        ["spec_alignment_score", t("Spec alignment"), "#8da84e"]
      ];
      chart.setOption({
        tooltip: { trigger: "axis" },
        legend: { type: "scroll", bottom: 0, textStyle: { color: "#42524a", fontFamily: "Manrope" } },
        grid: { top: 28, left: 42, right: 26, bottom: 68 },
        xAxis: { type: "category", data: chronologicallyOrdered.map(function (row) { return String(row.periodEnd || "period").slice(0, 10); }), axisLabel: { color: "#68766d" } },
        yAxis: { type: "value", axisLabel: { color: "#68766d" }, splitLine: { lineStyle: { color: "#e4e6de" } } },
        series: fields.map(function (field) { return { name: field[1], type: "line", smooth: true, showSymbol: false, data: chronologicallyOrdered.map(function (row) { return asNumber(row[field[0]]); }), lineStyle: { color: field[2], width: 2 }, itemStyle: { color: field[2] } }; })
      });
      new ResizeObserver(function () { chart.resize(); }).observe(element);
    });
  }

  function initTrace(root) {
    if (!window.cytoscape) return;
    root.querySelectorAll("[data-trace]").forEach(function (element) {
      if (element.dataset.graphReady === "true") return;
      var trace = parseJson(element.dataset.trace, { nodes: [], edges: [] });
      if (!trace.nodes?.length) return;
      element.dataset.graphReady = "true";
      var cy = window.cytoscape({
        container: element,
        elements: [
          ...trace.nodes.map(function (node) { return { data: { id: String(node.id), label: node.label || node.externalKey, type: node.nodeType || "NODE", key: node.externalKey || "" } }; }),
          ...trace.edges.map(function (edge) { return { data: { id: String(edge.id), source: String(edge.sourceNodeId), target: String(edge.targetNodeId), label: edge.relation || "links" } }; })
        ],
        style: [
          { selector: "node", style: { "background-color": "#5f846f", "label": "data(label)", "font-family": "Manrope", "font-size": 11, "color": "#19342d", "text-valign": "bottom", "text-margin-y": 8, "width": 30, "height": 30 } },
          { selector: "edge", style: { "width": 1.5, "line-color": "#9aa99a", "target-arrow-color": "#9aa99a", "target-arrow-shape": "triangle", "curve-style": "bezier", "label": "data(label)", "font-size": 8, "color": "#637568" } },
          { selector: ":selected", style: { "background-color": "#c8e879", "line-color": "#c8e879", "target-arrow-color": "#c8e879" } }
        ],
        layout: { name: "breadthfirst", directed: true, padding: 28, spacingFactor: 1.2 }
      });
      var selection = root.querySelector("#trace-selection");
      var nodes = cy.nodes();
      var activeIndex = 0;
      function announce(node) {
        cy.elements().unselect(); node.select();
        if (selection) selection.textContent = node.data("type") + ": " + node.data("label") + " · " + node.data("key");
      }
      cy.on("tap", "node", function (event) { announce(event.target); });
      element.addEventListener("keydown", function (event) {
        if (!nodes.length || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Enter"].includes(event.key)) return;
        event.preventDefault();
        if (event.key !== "Enter") activeIndex = (activeIndex + (event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 1) + nodes.length) % nodes.length;
        announce(nodes[activeIndex]);
      });
    });
  }

  function initTables(root) {
    if (!window.Tabulator) return;
    root.querySelectorAll("table[data-enhance-table]").forEach(function (table) {
      if (table.dataset.tableReady === "true" || !table.tBodies[0]?.rows.length) return;
      try {
        var headings = Array.from(table.tHead.rows[0].cells).map(function (cell, index) { return { title: cell.textContent.trim(), field: "field" + index, headerSort: true }; });
        var rows = Array.from(table.tBodies[0].rows).map(function (row) { return Object.fromEntries(Array.from(row.cells).map(function (cell, index) { return ["field" + index, cell.textContent.trim()]; })); });
        var mount = document.createElement("div"); mount.className = "tabulator-shell"; table.before(mount);
        new window.Tabulator(mount, { data: rows, columns: headings, layout: "fitDataStretch", pagination: true, paginationMode: "local", paginationSize: 10, movableColumns: false });
        table.hidden = true; table.dataset.tableReady = "true";
      } catch (_) { /* Server-rendered table remains the fallback. */ }
    });
  }

  function enhance(root) { initChart(root); initTrace(root); initTables(root); }
  document.addEventListener("DOMContentLoaded", function () { initHtmxCsrf(); enhance(document); });
}());
if (window.lucide) window.lucide.createIcons({attrs: {'aria-hidden': 'true', focusable: 'false'}});
