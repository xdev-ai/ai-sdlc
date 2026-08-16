package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;

import ai.xdev.aisdlc.config.NotificationProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class NotificationSecretCipherTest {
  @Test
  void encryptsWithRandomNonceAndRoundTripsConfiguredSecret() {
    NotificationProperties properties = new NotificationProperties();
    properties.setEncryptionKey(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
    NotificationSecretCipher cipher = new NotificationSecretCipher(properties);

    String first = cipher.encrypt("https://hooks.example.test/governance");
    String second = cipher.encrypt("https://hooks.example.test/governance");

    assertNotEquals("https://hooks.example.test/governance", first);
    assertNotEquals(first, second);
    assertEquals("https://hooks.example.test/governance", cipher.decrypt(first));
  }

  @Test
  void rejectsEncryptionWhenKeyIsNotA32ByteBase64UrlValue() {
    NotificationSecretCipher cipher = new NotificationSecretCipher(new NotificationProperties());

    IllegalStateException error = assertThrows(IllegalStateException.class, () -> cipher.encrypt("secret"));

    assertTrue(error.getMessage().contains("AISDLC_NOTIFICATION_ENCRYPTION_KEY"));
  }
}
