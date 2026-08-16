package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The property the audit chain depends on: the hashed form of a payload survives a round trip through {@code jsonb}.
 *
 * <p>PostgreSQL normalises jsonb on write — it reorders members and drops insignificant whitespace — so the text read
 * back during verification is not the text that was appended. Before canonicalisation the chain could never verify;
 * an end-to-end run reported {@code "Hash chain mismatch at sequence 1"} on a ledger with three valid events.
 */
class AuditPayloadCanonicalizerTest {
  @Test
  void formattingDifferencesIntroducedByJsonbDoNotChangeTheCanonicalForm() {
    // Exactly the difference observed: the value written had no space after the colon, the value read back did.
    assertEquals(AuditPayloadCanonicalizer.canonical("{\"slug\":\"acc-1\"}"),
        AuditPayloadCanonicalizer.canonical("{\"slug\": \"acc-1\"}"));
    assertEquals(AuditPayloadCanonicalizer.canonical("{\"a\":1,\"b\":2}"),
        AuditPayloadCanonicalizer.canonical("{\"b\": 2, \"a\": 1}"));
    assertEquals(AuditPayloadCanonicalizer.canonical("{\"outer\":{\"y\":1,\"x\":2}}"),
        AuditPayloadCanonicalizer.canonical("{\"outer\": {\"x\": 2, \"y\": 1}}"));
  }

  @Test
  void differentValuesStillProduceDifferentCanonicalForms() {
    assertNotEquals(AuditPayloadCanonicalizer.canonical("{\"slug\":\"a\"}"),
        AuditPayloadCanonicalizer.canonical("{\"slug\":\"b\"}"));
    // Array order is part of the value, not formatting, so it must survive.
    assertNotEquals(AuditPayloadCanonicalizer.canonical("{\"a\":[1,2]}"),
        AuditPayloadCanonicalizer.canonical("{\"a\":[2,1]}"));
  }

  @Test
  void absentPayloadMatchesTheColumnDefault() {
    assertEquals("{}", AuditPayloadCanonicalizer.canonical(null));
    assertEquals("{}", AuditPayloadCanonicalizer.canonical(""));
    assertEquals("{}", AuditPayloadCanonicalizer.canonical("   "));
    assertEquals("{}", AuditPayloadCanonicalizer.canonical("{}"));
  }

  @Test
  void malformedPayloadIsHashedVerbatimRatherThanDropped() {
    // A value that is not JSON must still chain. Silently substituting a default would let a corrupted payload
    // verify against a hash that never covered it.
    assertEquals("not json at all", AuditPayloadCanonicalizer.canonical("not json at all"));
  }

  @Test
  void canonicalFormIsStableAcrossRepeatedApplication() {
    String once = AuditPayloadCanonicalizer.canonical("{\"b\": 2, \"a\": 1}");
    assertEquals(once, AuditPayloadCanonicalizer.canonical(once));
  }
}
