# AI-SDLC — Governed AI-Assisted Delivery

[![Organization](https://img.shields.io/badge/Org-xDev%20AI-123450)](https://github.com/xdev-ai)

AI-SDLC là nền tảng **governance, traceability và quality evidence** dành cho đội ngũ phát triển phần mềm có sử dụng AI. Bản Build 1 tách validation tất định ở máy lập trình viên khỏi control plane trung tâm, nơi chính sách, phê duyệt và bằng chứng được kiểm định theo phân quyền.

## Thành phần Build 1

| Thành phần | Trách nhiệm | Nền tảng |
|---|---|---|
| `management-server` | REST control plane, policy, validation evidence, review, audit chain và metrics | Java 25.0.3, Spring Boot 4.1.0 |
| `portal` | Cổng vận hành **SSR** bảo mật, không phải SPA | Java 25.0.3, Spring Boot 4.1.0, Thymeleaf |
| `cli` | Validator offline tất định và đồng bộ evidence | Go 1.22+ |
| `keycloak` | Identity provider OAuth2/OIDC và nguồn realm roles | Keycloak 26.7.1 |
| `postgres` | Lưu trữ giao dịch bền vững cho control plane và Keycloak | PostgreSQL 18.6 |

> **Bất biến bảo mật.** CLI không gọi AI để ra quyết định governance, bắt buộc model pinning, từ chối `--bare`, và chỉ đồng bộ evidence. Các phase gate và quyết định review vẫn bắt buộc có phê duyệt của con người.

## Kiến trúc truy cập

Portal SSR là điểm vào trình duyệt công khai. Management API nằm trong private service network của topology Compose; Keycloak là authority xác thực phía sau boundary của portal/API. Khi triển khai production, portal và Keycloak cần được đặt sau reverse proxy TLS với hostname riêng; không public trực tiếp cổng management API.

## Phát triển local

1. Sao chép `.env.example` thành `.env` rồi thay thế toàn bộ development-only secrets.
2. Chạy `docker compose up --build` để khởi động topology local.
3. Mở `http://localhost:8080`; Keycloak nằm sau identity gateway tại `http://auth.localhost:8180` (các hostname `*.localhost` được browser hiện đại ánh xạ về loopback).
4. Chạy `mvn test` ở root để test server và portal; chạy `cd cli && go test ./...` để kiểm tra validator.

## Version policy

Stack được pin tại **Java 25.0.3 LTS**, **Spring Boot 4.1.0**, **PostgreSQL 18.6** và **Keycloak 26.7.1**. Mọi nâng version là thay đổi có governance: phải kiểm tra lại đầy đủ OAuth2, migration, authorization và luồng CLI evidence trước khi promotion.

## Cấu trúc repository

```text
management-server/    Spring Boot REST control plane
portal/               Spring Boot MVC + Thymeleaf SSR portal
cli/                  Deterministic Go validator và evidence client
infra/keycloak/       Realm import và identity configuration
docker-compose.yml    Local production-like topology
docs/                 Architecture, API và operating decisions
```

## Tài liệu tham khảo

[1] [Spring Boot project page — Spring Boot 4.1.0](https://spring.io/projects/spring-boot)

[2] [Oracle Java downloads — JDK 25 là bản LTS hiện tại](https://www.oracle.com/java/technologies/downloads/)

[3] [PostgreSQL 18.6 release announcement](https://www.postgresql.org/about/news/postgresql-186-1711-1615-1519-1424-and-19-beta-3-released-3365/)

[4] [Keycloak 26.7.1 release notes](https://www.keycloak.org/docs/latest/release_notes/index.html)
