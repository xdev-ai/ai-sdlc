# Hướng dẫn tích hợp module AI-SDLC

## Mục tiêu và boundary ổn định

AI-SDLC được triển khai như một Maven reactor và một control-plane service, nhưng các khả năng được tách theo bounded module. Đường tích hợp được hỗ trợ cho hệ thống bên ngoài là **API `/api/v1` có version**, OpenAPI được bảo vệ, OAuth2/JWT, và Go CLI. Không tích hợp bằng cách truy cập trực tiếp schema PostgreSQL, JPA entity hoặc repository nội bộ vì các thành phần đó không phải hợp đồng tương thích.

| Nhu cầu | Contract tích hợp được hỗ trợ | Không được phụ thuộc |
|---|---|---|
| Gửi validation xác định | `POST /api/v1/cli/projects/{projectId}/validation-runs` hoặc `aisdlc sync` | Bảng `validation_runs`, entity `ValidationRun` |
| Lưu artefact/evidence | `POST /api/v1/projects/{projectId}/evidence-assets` hoặc `aisdlc upload` | Bucket/key trực tiếp, credential S3/MinIO |
| Truy xuất chứng cứ | `GET /api/v1/projects/{projectId}/evidence-assets` và detail có presigned URL | URL object storage dài hạn hoặc danh sách bucket |
| Governance/review | REST resources tương ứng và audit verification endpoint | Tạo `ReviewDecision` hay `AuditEvent` qua SQL |
| Nhúng trong JVM cùng source tree | `ObjectStoragePort` là port thay thế được; service/repository package vẫn là implementation nội bộ | AWS SDK, MinIO SDK hoặc JPA repository của module khác |

> **Quy tắc tích hợp:** Mọi quyết định review/exception vẫn do một principal con người có role và project membership phù hợp gửi qua control plane. Integrator không được thay thế quyết định đó bằng agent hay job tự động.

## Tích hợp HTTP khuyến nghị

Một integration tạo OAuth2 client có scope/realm role tối thiểu, nhận JWT từ Keycloak và gửi token ở `Authorization: Bearer`. Project ID phải được chọn rõ ràng; role realm không đủ vì server luôn kiểm tra thêm membership trong project. API trả lỗi RFC 9457 `application/problem+json`; client chỉ retry lỗi transport, `429` và `5xx` theo backoff bị chặn. OpenAPI tương tác nằm ở `/swagger-ui.html`, còn document raw nằm ở `/v3/api-docs` cho admin.

Với Evidence Repository, client gửi multipart gồm `file`, `assetType`, `accessLevel` và tùy chọn `validationEvidenceId`. `X-Content-SHA256` cho phép server kiểm chứng bytes. `Idempotency-Key` phải giữ ổn định khi retry; nếu thiếu, server dẫn xuất một key từ provenance metadata và digest. Download luôn đi qua API authorization và trả presigned URL ngắn hạn, không phải endpoint public của object storage.[1]

## Điểm mở rộng lưu trữ

`ObjectStoragePort` là anti-corruption layer của module evidence. Default `S3ObjectStorageAdapter` dùng AWS SDK for Java 2.x với endpoint override và path-style addressing khi dùng MinIO. Một deployment muốn dùng S3-compatible provider khác chỉ thay adapter/configuration; không thay controller, audit, authorization hoặc persistence metadata. AWS khuyến nghị import SDK BOM cùng các service module/HTTP client thực sự dùng để giữ version alignment.[2]

Adapter thay thế phải đảm bảo bốn hành vi: ghi object private kèm SHA-256/project metadata, sinh presigned GET có thời hạn, áp retention Object Lock và xóa bù chỉ khi transaction metadata rollback. Adapter không được tự quyết định RBAC, sửa SHA-256 hoặc phát hành public URL.

| Property | Vai trò | Ví dụ local Compose |
|---|---|---|
| `AISDLC_EVIDENCE_S3_ENDPOINT` | Endpoint private S3-compatible | `http://minio:9000` |
| `AISDLC_EVIDENCE_S3_REGION` | Signing region | `us-east-1` |
| `AISDLC_EVIDENCE_S3_BUCKET` | Bucket Object Lock đã bootstrap | `aisdlc-evidence` |
| `AISDLC_EVIDENCE_S3_ACCESS_KEY` / `...SECRET_KEY` | Credential runtime của control plane | Nhận từ secret manager, không từ CLI/browser |
| `AISDLC_EVIDENCE_S3_FORCE_PATH_STYLE` | Tương thích MinIO/local endpoint | `true` |

## Versioning, kiểm thử và nâng cấp

Consumer phải pin image/binary phiên bản release, kiểm tra OpenAPI diff trước nâng cấp minor, và chạy contract smoke: tạo/upload cùng idempotency key hai lần, list theo project, verify download authorization của ba access level, tạo retention dài hơn, và audit-chain verification. Không giả định class Java không public hoặc schema migration Flyway là API tương thích.

Maven artifact Java client độc lập (`sdk/`) chưa được công bố. Cho đến khi artifact đó có semantic versioning và compatibility policy riêng, HTTP/OpenAPI và CLI là các integration boundary có hỗ trợ chính thức.

## References

[1] [AWS SDK for Java 2.x — S3 presigning](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html)

[2] [AWS SDK for Java 2.x — Maven setup and BOM alignment](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/setup-project-maven.html)
