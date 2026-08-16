# ADR 0001: Stay on Java 25 LTS until the next LTS

**Status:** Accepted — decision closed
**Date:** 2026-08-17
**Supersedes:** the open deferral behind PRs #4, #7, #17, and #21

## Context

Dependabot repeatedly proposed moving both the Maven build image and the `eclipse-temurin` runtime image from Java 25 to Java 26. Those pull requests were closed four times without a recorded decision, on the grounds that `README.md` pins **Java 25.0.3 LTS** and the version policy requires OAuth2, migration, authorization, and CLI evidence flows to be revalidated before a runtime upgrade is promoted.

That left the question open rather than answered: the upgrade was blocked by a precondition nobody had attempted to satisfy.

## Revalidation actually performed

The precondition has now been met. On Java 26:

| Check | Result |
|---|---|
| `mvn -B test` on JDK 26 | BUILD SUCCESS, full suite |
| Runtime image on `eclipse-temurin:26-jre-alpine` | builds, starts, readiness `UP` |
| End-to-end governed flow against that runtime | **32/32 assertions pass** |

So Java 26 is **not** a technical blocker. Had the decision rested only on "does it work", the answer is yes.

## Decision

**Remain on Java 25 and do not adopt Java 26.**

The reason is not compatibility, it is support lifecycle. Java 25 is an LTS release; **Java 26 is not**. Adopting a non-LTS runtime would place a governance and audit platform on a release line that stops receiving updates in six months, and would force another unplanned runtime migration inside that window. For a system whose value is the durability of its evidence, trading a supported runtime for a newer one is the wrong trade.

The existing `README.md` pin therefore stands, and it stands for a stated reason rather than by inertia.

## Consequences

- `maven.compiler.release` stays at 25; both images stay on `eclipse-temurin:25`.
- The Dependabot ignore rules in `.github/dependabot.yml` already enforce this for both the `maven` build image and the `eclipse-temurin` runtime image, so the proposal will not keep reappearing for the tags already seen.
- Patch and minor updates within the 25 line continue to flow, so security refreshes are not blocked.
- CI continues to build and test on JDK 25.

## Revisit trigger

Reopen this decision when **the next Java LTS is generally available** (Java 29, on the two-year cadence), or earlier if a security advisory affects Java 25 with no backport. At that point rerun exactly the revalidation recorded above — the full test suite plus `scripts/end-to-end-acceptance.sh` against the candidate runtime — before promoting.

A future non-LTS release is not a reason to revisit. That is the point of writing this down.
