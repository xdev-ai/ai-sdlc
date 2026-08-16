package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.config.NotificationProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class NotificationSecretCipher {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final NotificationProperties properties;
  public NotificationSecretCipher(NotificationProperties properties) { this.properties = properties; }
  public String encrypt(String plaintext) {
    ensureConfigured();
    try {
      byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    } catch (Exception error) { throw new IllegalStateException("Unable to encrypt notification channel secret", error); }
  }
  public String decrypt(String ciphertext) {
    ensureConfigured();
    try {
      String[] values = ciphertext.split("\\.", -1);
      if (values.length != 2) throw new IllegalArgumentException("Invalid encrypted notification secret");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, Base64.getUrlDecoder().decode(values[0])));
      return new String(cipher.doFinal(Base64.getUrlDecoder().decode(values[1])), StandardCharsets.UTF_8);
    } catch (Exception error) { throw new IllegalArgumentException("Unable to decrypt notification channel secret", error); }
  }
  private byte[] key() { return Base64.getUrlDecoder().decode(properties.getEncryptionKey()); }
  private void ensureConfigured() { if (!properties.isConfigured()) throw new IllegalStateException("AISDLC_NOTIFICATION_ENCRYPTION_KEY must be a 32-byte base64url value"); }
}
