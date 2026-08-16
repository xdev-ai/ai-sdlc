# AI-SDLC — Governed AI-Assisted Delivery

[![Organization](https://img.shields.io/badge/Org-xDev%20AI-123450)](https://github.com/xdev-ai)

AI-SDLC is a **governance, traceability, and quality-evidence platform** for software-development teams that use AI. Build 1 separates deterministic validation on developer machines from a centralized control plane, where policy, approval, and evidence are authorized and verified.

## Build 1 components

| Component | Responsibility | Platform |
|---|---|---|
| `management-server` | REST control plane, policy, validation evidence, review, audit chain, and metrics | Java 25.0.3, Spring Boot 4.1.0 |
| `portal` | Secure **SSR** operational portal, not an SPA | Java 25.0.3, Spring Boot 4.1.0, Thymeleaf |
| `cli` | Deterministic offline validator and evidence client | Go 1.22+ |
| `keycloak` | OAuth2/OIDC identity provider and realm-role authority | Keycloak 26.7.1 |
| `postgres` | Durable transactional storage for the control plane and Keycloak | PostgreSQL 18.6 |

> **Security invariants.** The CLI does not call AI to make governance decisions, requires a pinned model, rejects `--bare`, and only synchronizes evidence. Phase gates and review decisions still require human approval.

## Access architecture

The SSR portal is the public browser entry point. The Management API runs on the Compose topology's private service network; Keycloak is the authentication authority behind the portal/API boundary. In production, the portal and Keycloak must sit behind TLS reverse proxies with dedicated hostnames; never expose the Management API port directly.

## Local development

1. Copy `.env.example` to `.env`, then replace all development-only secrets.
2. Run `docker compose up --build` to start the local topology.
3. Open `http://localhost:8080`; Keycloak is behind the identity gateway at `http://auth.localhost:8180` (modern browsers map `*.localhost` hostnames to loopback).
4. Run `mvn test` at the repository root to test server and portal; run `cd cli && go test ./...` to test the validator.

## Language policy

Repository-facing material—README, documentation, issue and pull-request content, release notes, API descriptions, and developer-facing code comments—uses English. The portal supports English and Vietnamese; Vietnamese text is deliberately confined to the localization resource that is rendered to end users. See [`docs/localization.md`](docs/localization.md).

## Version policy

The stack is pinned to **Java 25.0.3 LTS**, **Spring Boot 4.1.0**, **PostgreSQL 18.6**, and **Keycloak 26.7.1**. Every version upgrade is a governed change: OAuth2, migrations, authorization, and CLI evidence flows must be revalidated before promotion.

## Repository layout

```text
management-server/    Spring Boot REST control plane
portal/               Spring Boot MVC + Thymeleaf SSR portal
cli/                  Deterministic Go validator and evidence client
infra/keycloak/       Realm import and identity configuration
docker-compose.yml    Local production-like topology
docs/                 Architecture, API, and operating decisions
```

## References

[1] [Spring Boot project page — Spring Boot 4.1.0](https://spring.io/projects/spring-boot)

[2] [Oracle Java downloads — JDK 25 LTS](https://www.oracle.com/java/technologies/downloads/)

[3] [PostgreSQL 18.6 release announcement](https://www.postgresql.org/about/news/postgresql-186-1711-1615-1519-1424-and-19-beta-3-released-3365/)

[4] [Keycloak 26.7.1 release notes](https://www.keycloak.org/docs/latest/release_notes/index.html)
