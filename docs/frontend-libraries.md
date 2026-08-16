# Frontend library strategy

## Decision

The portal remains **Spring MVC + Thymeleaf SSR-first**. JavaScript enhances only the areas that need rich interaction; all essential content, navigation, tables, and forms must have a server-rendered HTML version that remains usable when JavaScript is disabled.

| Library | Single responsibility | Portal usage |
|---|---|---|
| HTMX 2.0.10 | Partial HTML request/response | Refresh dashboard widgets, submit administrative forms, review decisions, progressive pagination and filtering |
| Alpine.js 3.15.0 | Small local UI state | Menus, modals, disclosures, optimistic visual state; never an independent business-state store |
| Apache ECharts 6.0.0 | Quality/DORA charts | Deployment, lead-time, change-failure, review-latency, rework, and alignment-score trends |
| Cytoscape.js 3.33.1 | Interactive traceability graph | Pan, zoom, filter, and focus nodes in the requirement → specification → task → test → evidence chain |
| Tabulator 6.3.1 | Enhance large data tables | Sorting, filtering, and exporting for validation, review queue, and audit ledger; SSR tables remain the fallback |
| Lucide 0.468.0 | Icon system | Semantic icons through a local SVG sprite or asset; no icon font |

Versions are pinned in `portal/src/main/resources/static/vendor/` and are not loaded from a CDN at runtime. This avoids third-party runtime dependencies in the administrative interface and supports a strict Content Security Policy. Each version must be revalidated when dependencies are upgraded.

## Guardrails

HTMX calls only SSR controllers that return HTML fragments and always passes through Spring Security CSRF and server-side authorization. Alpine does not call the REST control plane directly. ECharts and Cytoscape render only JSON supplied by server-side controllers within an authorized project scope. Tabulator is progressive enhancement: if a script fails or JavaScript is disabled, the HTML `<table>` remains visible.

Charts and graphs are not independently sufficient for accessibility. The portal retains a numerical table or textual summary alongside each chart, an `aria-label` for every canvas container, keyboard focus for graph nodes, and an SSR traceability list alongside Cytoscape.

## React Islands architecture

React is used **only as bounded islands**; it does not replace Spring MVC/Thymeleaf with an SPA. Spring Boot continues to produce the initial HTML and controls OAuth2/Keycloak sessions, CSRF, authorization, and server-side fallback. React mounts only into explicitly marked containers after the HTML document has loaded.

| Island | SSR fallback | React responsibility | Security boundary |
|---|---|---|---|
| Quality Analytics | Summary cards and metrics table | Cross-filtering, brushing, period comparison, ECharts lifecycle | Receives only controller-provided data from a project-membership-checked scope |
| Traceability Explorer | Requirement → evidence trace table/list | Cytoscape view, search, focus, graph navigation | Does not acquire tokens or call APIs; data is released through the portal BFF |
| Evidence Workspace | Validation/findings table | Local filters, detail drawer, saved client view | Evidence mutation remains an HTML form or HTMX action protected by CSRF |
| Review Decision | Fallback SSR POST form | Rationale disclosure, validation, confirmation state | POSTs to the Spring MVC BFF, which then forwards the token to the Management API |

The build uses **React 19.2.8**, **React DOM 19.2.8**, **Vite 8.2.1**, and **@vitejs/plugin-react 6.0.5**. Vite emits a hashed bundle and manifest; Maven runs the Node build before the resource phase and then copies the manifest and bundles into `classpath:/static/react/`. Thymeleaf resolves the manifest through a server-side helper and never hard-codes a build filename.

React islands start with `createRoot`, not `hydrateRoot`, because the Thymeleaf fallback and the interactive React section own separate DOM regions. This removes hydration mismatches caused by React requiring exactly identical initial markup. If an island requires true SSR hydration in the future, a React server renderer must be added to the build pipeline and the same props snapshot must be used on both server and client.

> React must not store access tokens, perform OAuth redirects, or bypass CSRF forms. Spring Boot remains the only BFF and policy-enforcement point between the browser and the control plane.

## References

[1] [HTMX documentation](https://htmx.org/docs/) describes htmx as a mechanism for directly triggering HTTP/AJAX with HTML attributes and receiving HTML from the server.

[2] [Alpine.js Start Here](https://alpinejs.dev/start-here) documents the `x-data`, `x-on`, `x-show`, and `x-model` directives for lightweight local state.

[3] [Apache ECharts Get Started](https://echarts.apache.org/handbook/en/get-started/) confirms the pattern of initializing an instance on a container with explicit dimensions and rendering with `setOption`.

[4] [Cytoscape.js](https://js.cytoscape.org/) describes a graph/network library with JSON, layouts, selectors/queries, desktop and touch gestures, and an MIT license.

[5] [React `hydrateRoot`](https://react.dev/reference/react-dom/client/hydrateRoot) requires client React output to exactly match server-rendered HTML; differences must be treated as bugs.

[6] [Vite Backend Integration](https://vite.dev/guide/backend-integration) defines a build manifest that maps source entries to hashed bundles so a traditional backend can render the correct scripts, stylesheets, and preload dependencies.
