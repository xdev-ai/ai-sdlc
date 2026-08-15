# AI-SDLC Build 1 — Hướng dẫn vận hành

## Mục đích

Build 1 cung cấp một **control plane cho AI-assisted software delivery**. Hệ thống không tự ra quyết định thay con người: Go CLI thực thi validation tất định, Spring Boot áp dụng policy và quyền hạn, PostgreSQL lưu evidence/audit, còn portal SSR trình bày trạng thái cho người có thẩm quyền.

> **Bất biến vận hành:** CLI không gọi AI; `--bare` bị cấm; `--model` bắt buộc là model được pin theo revision; review/phase gate chỉ hoàn tất sau quyết định của con người.

## Topology local

| Thành phần | Công nghệ | Port public | Vai trò |
|---|---:|---:|---|
| Portal | Spring MVC + Thymeleaf SSR | `8080` | Cổng thao tác của người dùng |
| Identity gateway | Nginx | `8180` | Public edge cho Keycloak tại `auth.localhost` |
| Keycloak | 26.7.1 | Không public trực tiếp | OpenID Connect, realm roles |
| Management server | Spring Boot REST API | Không public trực tiếp | Policy, evidence, review và audit |
| PostgreSQL | 18.6 | Không public trực tiếp | Transactional control-plane store |
| MinIO | S3-compatible object storage | Không public trực tiếp | Bytes Evidence Repository, versioning/Object Lock bucket |
| CLI | Go | Không áp dụng | Validation local và evidence sync |

Keycloak được đặt **sau identity gateway**. Trong Docker Compose, services nội bộ không được công bố port tùy tiện; portal là lối vào ứng dụng, identity gateway là lối vào OIDC.

## Khởi động local

Sao chép file biến môi trường và thay toàn bộ giá trị bí mật bằng secrets riêng. Không commit `.env`.

```bash
cp .env.example .env
docker compose up --build
```

Sau khi health checks hoàn tất, mở `http://localhost:8080`. Portal chuyển người dùng đến Keycloak qua `http://auth.localhost:8180`. Callback đã được cố định tại `/login/oauth2/code/keycloak`.

## Vai trò

| Vai trò Keycloak | Quyền Build 1 |
|---|---|
| `admin` | Quản lý organization/project, đăng ký/pin kit, policy/constitution, capability grant và audit visibility |
| `developer` | Đẩy evidence từ CLI, xem validation/traceability/quality trong project có membership |
| `reviewer` | Xem project scope và ra quyết định APPROVED/REJECTED cho review/phase gates |

Role realm không đủ để truy cập dữ liệu dự án: management server luôn kiểm tra thêm **project membership**. Đây là lớp bảo vệ chống việc một developer/reviewer có quyền tổ chức nhưng đọc project ngoài scope.

## Quy trình developer

Tạo `spec-kit` chứa tối thiểu `constitution.md`, `spec.md` và `tasks.md`. Khởi tạo `.aisdlc.yml` một lần, commit cấu hình governance không chứa bí mật, rồi chạy validation với một model pin rõ revision. Validation không gọi model — model pin chỉ được lưu như provenance bắt buộc.

```bash
cd cli
go run ./cmd/aisdlc init \
  --project <project-uuid> \
  --api-url http://localhost:8081 \
  --spec-dir ../my-project/spec-kit \
  --kit-version core@1.0.0 \
  --model provider/model@revision
go run ./cmd/aisdlc validate --config .aisdlc.yml --format json --out validation-result.json

AISDLC_ACCESS_TOKEN="$TOKEN" go run ./cmd/aisdlc sync \
  --config .aisdlc.yml \
  --result validation-result.json \
  --idempotency-key <ci-run-key>
```

`sync` gọi `POST /api/v1/cli/projects/{projectId}/validation-runs`. Key idempotency giúp CI retry mà không tạo evidence hoặc audit event trùng. API lưu validation run, findings, evidence và audit event trong cùng unit of work.

Để lưu chứng cứ có kích thước lớn hoặc artefact governance, dùng `upload`. CLI tính SHA-256 cục bộ, truyền digest cho management server kiểm chứng và **không** nhận hoặc lưu credential MinIO/S3.

```bash
AISDLC_ACCESS_TOKEN="$TOKEN" go run ./cmd/aisdlc upload ./validation-result.json \
  --config .aisdlc.yml \
  --asset-type VALIDATION \
  --access-level PROJECT \
  --json
```

MinIO chỉ hiện diện trong network nội bộ Compose. `evidence-bucket-init` tạo bucket `AISDLC_EVIDENCE_S3_BUCKET` với Object Lock một cách idempotent trước khi management server khởi động. Local `.env` bắt buộc có `AISDLC_EVIDENCE_S3_ACCESS_KEY` và `AISDLC_EVIDENCE_S3_SECRET_KEY`; thay hai giá trị mẫu bằng secret riêng và không commit file.

## REST resource map

| Resource | Mục đích |
|---|---|
| `/api/v1/organizations/{organizationId}/projects` | Project portfolio và tạo project có kiểm soát |
| `/api/v1/organizations/{organizationId}/spec-kits` | Registry core/extension/preset/override, pinning theo version |
| `/api/v1/projects/{projectId}/validation-runs` | Dashboard evidence đã đồng bộ |
| `/api/v1/projects/{projectId}/traceability` | Nodes/edges requirement → spec → task → test → evidence |
| `/api/v1/projects/{projectId}/policies` và `/constitutions` | Governance-as-data theo version |
| `/api/v1/projects/{projectId}/review-items` | Human review và phase gate decision |
| `/api/v1/projects/{projectId}/quality-metrics` | DORA counter-metrics và spec alignment |
| `/api/v1/projects/{projectId}/evidence-assets` | Upload/list evidence metadata; detail trả presigned download URL sau authorization; retention/soft delete được audit-backed |
| `/api/v1/organizations/{organizationId}/audit-events` | Audit ledger append-only hash-chain |

## Audit integrity

Mỗi event nhận `sequence`, `previous_hash` và `event_hash`. Database migration cài trigger cấm cả `UPDATE` và `DELETE` lên `audit_events`; application cũng không cung cấp endpoint sửa/xóa audit. Bất kỳ thao tác validation, policy, review, exception hoặc agent launch được hỗ trợ phải đi qua `AuditService`.

## Kiểm tra trước khi merge

```bash
mvn test
mvn -DskipTests package
cd cli && go test ./... && go build ./cmd/aisdlc
bash ../scripts/verify-production.sh
```

Build hiện đã pass Java unit test, Go test và Maven package. Integration regression đầy đủ cần Docker daemon để khởi động PostgreSQL, Keycloak và identity gateway; môi trường xây dựng hiện tại không có Docker daemon nên bước đó được giữ rõ là pending, không được coi là hoàn tất. Xem [`cli.md`](cli.md), [`control-plane-api.md`](control-plane-api.md), [`portal-workflows.md`](portal-workflows.md), [`continuous-delivery.md`](continuous-delivery.md) và [`production-operations.md`](production-operations.md) cho contract production chi tiết.
