# Frontend library strategy

## Quyết định

Portal vẫn là **Spring MVC + Thymeleaf SSR-first**. JavaScript chỉ nâng cấp các khu vực cần tương tác phong phú; mọi nội dung trọng yếu, điều hướng, bảng dữ liệu và form đều phải có HTML server-rendered dùng được khi JavaScript tắt.

| Thư viện | Trách nhiệm duy nhất | Cách dùng trong portal |
|---|---|---|
| HTMX 2.0.10 | Partial HTML request/response | Refresh dashboard widget, submit form quản trị, review decision, progressive pagination/filter |
| Alpine.js 3.15.0 | Local UI state nhỏ | Menu, modal, disclosure, optimistic visual state; không giữ business state độc lập server |
| Apache ECharts 6.0.0 | Biểu đồ quality/DORA | Trend deployment, lead time, change-failure, review latency, rework và alignment score |
| Cytoscape.js 3.33.1 | Interactive traceability graph | Pan/zoom/filter/focus node cho chuỗi requirement → spec → task → test → evidence |
| Tabulator 6.3.1 | Enhance table khi dữ liệu đủ lớn | Sort/filter/export cho validation, review queue, audit ledger; giữ table SSR là fallback |
| Lucide 0.468.0 | Icon system | Icon semantic qua SVG sprite/local asset; không dùng icon font |

Các phiên bản sẽ được pin trong `portal/src/main/resources/static/vendor/`, không lấy từ CDN lúc runtime. Điều này tránh phụ thuộc bên thứ ba ở giao diện quản trị và cho phép chính sách Content Security Policy nghiêm ngặt. Phiên bản cụ thể phải được kiểm tra lại khi nâng cấp dependency.

## Guardrails

HTMX chỉ gọi các controller SSR trả về HTML fragment, luôn qua Spring Security CSRF và authorization ở server. Alpine không được gọi REST control-plane trực tiếp. ECharts và Cytoscape chỉ render dữ liệu JSON do controller server-side cung cấp trong phạm vi project đã được cấp quyền. Tabulator dùng progressive enhancement: nếu script lỗi hoặc JavaScript tắt, `<table>` HTML vẫn hiển thị.

Biểu đồ và graph không tự đủ về accessibility. Portal sẽ duy trì bảng số liệu/tóm tắt text cạnh biểu đồ, `aria-label` cho canvas container, keyboard focus cho node và một traceability list SSR song song với Cytoscape.

## React Islands architecture

React được dùng **có giới hạn theo island**, không thay thế Spring MVC/Thymeleaf thành SPA. Spring Boot tiếp tục tạo HTML ban đầu, kiểm soát session OAuth2/Keycloak, CSRF, authorization và server-side fallback. React chỉ mount vào các container được đánh dấu rõ ràng sau khi tài liệu HTML đã tải.

| Island | SSR fallback | React responsibility | Boundary bảo mật |
|---|---|---|---|
| Quality Analytics | Summary cards và bảng metrics | Cross-filter, brush, compare periods, ECharts lifecycle | Chỉ nhận data scope từ controller đã kiểm tra project membership |
| Traceability Explorer | Trace table/list theo requirement → evidence | Cytoscape view, search, focus, graph navigation | Không tự gọi token/API; data phát hành qua portal BFF |
| Evidence Workspace | Bảng validation/findings | Local filters, detail drawer, saved client view | Evidence mutation vẫn qua HTML form/HTMX chịu CSRF |
| Review Decision | Form SSR POST có fallback | Rationale disclosure, validation/confirmation state | POST về Spring MVC BFF, sau đó server forward token tới management API |

Build dùng **React 19.2.8**, **React DOM 19.2.8**, **Vite 8.2.1** và **@vitejs/plugin-react 6.0.5**. Vite xuất bundle hashed kèm manifest; Maven chạy build Node trước resource phase, sau đó copy manifest/bundle vào `classpath:/static/react/`. Thymeleaf tham chiếu manifest qua một helper server-side, không hard-code filename build.

React islands khởi đầu bằng `createRoot`, không `hydrateRoot`, vì phần fallback Thymeleaf và phần React tương tác là hai DOM ownership tách biệt. Điều này loại trừ hydration mismatch do React yêu cầu markup ban đầu phải đồng nhất tuyệt đối. Khi một island cần hydrate SSR thật trong tương lai, server renderer React phải được thêm vào build pipeline và cùng một props snapshot phải được dùng ở cả server/client.

> React không được lưu access token, thực hiện OAuth redirect, hoặc bỏ qua form CSRF. Spring Boot vẫn là BFF và policy enforcement point duy nhất giữa browser với control plane.

## Nguồn tham khảo

[1] [HTMX documentation](https://htmx.org/docs/) mô tả htmx như cơ chế kích hoạt HTTP/AJAX trực tiếp bằng thuộc tính HTML và phản hồi HTML từ server.

[2] [Alpine.js Start Here](https://alpinejs.dev/start-here) mô tả các directive `x-data`, `x-on`, `x-show` và `x-model` cho state cục bộ nhẹ.

[3] [Apache ECharts Get Started](https://echarts.apache.org/handbook/en/get-started/) xác nhận mô hình khởi tạo instance trên container có kích thước rõ ràng và render với `setOption`.

[4] [Cytoscape.js](https://js.cytoscape.org/) mô tả thư viện graph/network hỗ trợ JSON, layout, selector/query, gesture desktop/touch và MIT license.

[5] [React `hydrateRoot`](https://react.dev/reference/react-dom/client/hydrateRoot) yêu cầu output React ở client khớp hoàn toàn HTML đã render trên server; khác biệt phải được xử lý như bug.

[6] [Vite Backend Integration](https://vite.dev/guide/backend-integration) quy định build manifest ánh xạ entry source sang bundle hashed, để traditional backend render chính xác script, stylesheet và preload dependencies.
