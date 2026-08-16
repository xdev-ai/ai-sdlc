package ai.xdev.aisdlc.domain;

import ai.xdev.aisdlc.domain.DomainTypes.PolicyBundleLifecycle;
import java.time.Instant;
import java.util.UUID;

/** Immutable projection of a versioned CEL policy bundle. Expressions are never executable Java code. */
public record PolicyBundle(
    UUID id,
    UUID projectId,
    String bundleKey,
    String semanticVersion,
    String description,
    String celExpression,
    String sourceSha256,
    String fixtureJson,
    boolean dryRunDefault,
    PolicyBundleLifecycle lifecycleStatus,
    String compilationError,
    Instant checkedAt,
    Instant createdAt) {}
