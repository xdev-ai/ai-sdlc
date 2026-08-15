# Production Dependency Decisions

This record captures externally verified dependencies introduced by the production-hardening workstream. Versions are pinned in the management-server Maven module to keep builds reproducible.

| Dependency | Pinned version | Purpose | Decision basis |
|---|---:|---|---|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `3.1.0` | Generates the protected OpenAPI document and browser documentation for the Spring MVC control plane. | The project documentation states Spring Boot 4 support and publishes the corresponding Maven coordinate.[1] |
| `com.bucket4j:bucket4j_jdk17-core` | `8.19.0` | Provides token-bucket limits for API endpoints. | Maven Central lists the JDK 17 core artifact and the selected version.[2] |
| `net.logstash.logback:logstash-logback-encoder` | `9.0` | Emits structured JSON logs and preserves MDC correlation identifiers. | The project release information identifies version 9.0 as a Java 17+ compatible major release.[3] |

The current rate limiter is intentionally in-memory and applies to each server instance. The operational runbook must require a distributed backend before horizontally scaling the control plane so rate policies remain globally consistent.

## References

[1]: https://springdoc.org/ "springdoc-openapi: Spring Boot 4 support and Maven setup"
[2]: https://central.sonatype.com/artifact/com.bucket4j/bucket4j_jdk17-core "Maven Central: Bucket4j JDK 17 Core 8.19.0"
[3]: https://github.com/logfellow/logstash-logback-encoder/releases "Logstash Logback Encoder releases"
