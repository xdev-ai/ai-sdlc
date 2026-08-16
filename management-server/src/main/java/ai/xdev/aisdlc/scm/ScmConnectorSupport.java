package ai.xdev.aisdlc.scm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verification primitives shared by the connectors. Every comparison here is constant time. */
public final class ScmConnectorSupport {
  private ScmConnectorSupport() {}

  /** Case-insensitive header lookup; providers are inconsistent about casing and a miss must not read as absent. */
  public static String header(Map<String, String> headers, String name) {
    if (headers == null || name == null) return null;
    return headers.get(name.toLowerCase(java.util.Locale.ROOT));
  }

  /** Constant-time equality for a shared secret compared as an opaque token. */
  public static boolean secretEquals(String expected, String supplied) {
    if (expected == null || expected.isBlank() || supplied == null) return false;
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
  }

  /** Constant-time HMAC-SHA256 verification of {@code <prefix><hex>} against the payload. */
  public static boolean hmacSha256Matches(String secret, byte[] payload, String suppliedSignature, String prefix) {
    if (secret == null || secret.isBlank() || payload == null || suppliedSignature == null) return false;
    if (!suppliedSignature.startsWith(prefix)) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = prefix + HexFormat.of().formatHex(mac.doFinal(payload));
      return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), suppliedSignature.getBytes(StandardCharsets.US_ASCII));
    } catch (Exception unusable) {
      // A missing algorithm or an unusable key is a rejection, not a server error surfaced to the sender.
      return false;
    }
  }

  /**
   * A deterministic delivery identifier for a provider that sends none. Two replays of the same bytes produce the
   * same key, so shared idempotency still holds.
   */
  public static String derivedDeliveryId(String prefix, byte[] payload) {
    try {
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
      return prefix + "-" + digest.substring(0, 32);
    } catch (Exception exception) {
      throw new IllegalStateException("Digest unavailable", exception);
    }
  }
}
