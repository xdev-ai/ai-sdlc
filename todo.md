# AI-SDLC Build 1 TODO

- [x] Pin Java 25.0.3 LTS, Spring Boot 4.1.0, PostgreSQL 18.6 và Keycloak 26.7.1.
- [x] Tạo monorepo gồm management-server Spring Boot, portal SSR Thymeleaf, Go CLI và Docker Compose.
- [x] Cấu hình Keycloak realm và các role `admin`, `developer`, `reviewer`.
- [x] Xây REST control plane cho projects, validation evidence, kit registry, policy, constitution, traceability, reviews, quality metrics và audit.
- [x] Áp dụng role checks và project-membership checks tại API/service boundary.
- [x] Xây append-only audit ledger với database trigger cấm UPDATE và DELETE.
- [x] Tạo portal SSR responsive cho dashboard, projects, kits, validations, traceability, governance, review queue, quality và audit.
- [x] Tạo Go CLI deterministic với model pinning, cấm `--bare`, evidence digest và idempotent sync.
- [x] Viết và chạy Java unit tests cho RBAC, ingest evidence, review decision và audit append-only.
- [x] Kiểm tra portal SSR công khai, OAuth2 callback configuration và build artifact.
- [ ] Chạy integration regression PostgreSQL/Keycloak qua Docker Compose (chưa thể chạy vì môi trường build hiện tại không có Docker daemon).
- [x] Hoàn thiện tài liệu thao tác và kiến trúc Build 1.
- [x] Push monorepo vào `xdev-ai/ai-sdlc` và xác minh repository sạch.
