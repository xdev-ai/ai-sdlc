package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.config.GitHubAppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class GitHubWebhookSignatureVerifier {
  private final GitHubAppProperties properties;
  public GitHubWebhookSignatureVerifier(GitHubAppProperties properties) { this.properties = properties; }
  public boolean isValid(byte[] payload, String suppliedSignature) {
    if (!properties.isWebhookConfigured() || suppliedSignature == null || !suppliedSignature.startsWith("sha256=")) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] expected = ("sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(payload))).getBytes(StandardCharsets.US_ASCII);
      return MessageDigest.isEqual(expected, suppliedSignature.getBytes(StandardCharsets.US_ASCII));
    } catch (Exception error) {
      throw new IllegalStateException("Unable to verify GitHub webhook signature", error);
    }
  }
}
