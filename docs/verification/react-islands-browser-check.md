# React Islands browser check

Date: 2026-08-15

The Spring Boot portal was started locally after the React Islands pipeline update. The public SSR landing page rendered successfully at `http://localhost:8080/` with the established dark governance design, readable typography, responsive visual hierarchy, and live `Open control plane` OAuth2 entry point.

The final runtime also served the Vite manifest and hashed React bundle under `/react/`. Private interactive workspaces require an authenticated Keycloak session and a selected organization/project with real data; the browser check did not fabricate those records.

The fallback architecture is preserved: server-rendered tables, node lists, review forms, and empty states remain present whenever JavaScript is unavailable or no governed data is in scope.
