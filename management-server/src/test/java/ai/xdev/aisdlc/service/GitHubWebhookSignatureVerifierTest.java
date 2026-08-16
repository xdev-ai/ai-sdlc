package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;

import ai.xdev.aisdlc.config.GitHubAppProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class GitHubWebhookSignatureVerifierTest {
  @Test void verifiesExpectedHmacSha256AgainstTheRawPayload() throws Exception {
    GitHubAppProperties properties = new GitHubAppProperties();
    properties.setWebhookSecret("webhook-secret");
    byte[] payload = "{\"repository\":{\"full_name\":\"xdev-ai/ai-sdlc\"}}".getBytes(StandardCharsets.UTF_8);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(payload));

    GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier(properties);

    assertTrue(verifier.isValid(payload, signature));
    assertFalse(verifier.isValid(payload, "sha256=" + "0".repeat(64)));
    assertFalse(verifier.isValid("modified".getBytes(StandardCharsets.UTF_8), signature));
    assertFalse(verifier.isValid(payload, null));
  }
}
