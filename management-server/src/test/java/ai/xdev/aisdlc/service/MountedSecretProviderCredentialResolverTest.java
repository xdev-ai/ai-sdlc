package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.KeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MountedSecretProviderCredentialResolverTest {
  private static Path writeSecret(Path directory, String name, String content) throws IOException {
    Path file = directory.resolve(name);
    Files.writeString(file, content);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    return file;
  }

  private static void writeKeyStore(Path directory, String name, String password) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, password.toCharArray());
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256, new SecureRandom());
    keyStore.setEntry("client", new KeyStore.SecretKeyEntry(generator.generateKey()),
        new KeyStore.PasswordProtection(password.toCharArray()));
    Path file = directory.resolve(name + ".p12");
    try (OutputStream stream = Files.newOutputStream(file)) {
      keyStore.store(stream, password.toCharArray());
    }
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    writeSecret(directory, name + ".p12.pass", password);
  }

  @Test
  void resolvesAMountedAuthorizationValueWithoutMtls(@TempDir Path mount) throws Exception {
    writeSecret(mount, "provider-a", "Bearer mounted-token\n");
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    var material = resolver.resolve("mount:provider-a", null, false);
    assertEquals("Bearer mounted-token", material.authorizationHeader());
    assertNull(material.sslContext());
  }

  @Test
  void rejectsAReferenceThatIsNotAMountedReference(@TempDir Path mount) throws Exception {
    writeSecret(mount, "provider-a", "Bearer mounted-token");
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    assertThrows(IllegalStateException.class, () -> resolver.resolve("provider-a", null, false));
    assertThrows(IllegalStateException.class, () -> resolver.resolve("vault:provider-a", null, false));
    assertThrows(IllegalStateException.class, () -> resolver.resolve(null, null, false));
  }

  @Test
  void rejectsAReferenceThatWouldEscapeTheMount(@TempDir Path mount) throws Exception {
    Path outside = mount.getParent().resolve("outside-secret");
    Files.writeString(outside, "Bearer escaped");
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:../outside-secret", null, false));
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:sub/../../outside-secret", null, false));
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:/etc/passwd", null, false));
  }

  @Test
  void rejectsMaterialReadableBeyondItsOwner(@TempDir Path mount) throws Exception {
    Path file = writeSecret(mount, "provider-a", "Bearer mounted-token");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:provider-a", null, false));
  }

  @Test
  void rejectsASymbolicLinkStandingInForMountedMaterial(@TempDir Path mount) throws Exception {
    Path target = mount.getParent().resolve("linked-secret");
    Files.writeString(target, "Bearer linked");
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
    Files.createSymbolicLink(mount.resolve("provider-a"), target);
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:provider-a", null, false));
  }

  @Test
  void rejectsMissingEmptyAndControlCharacterCredentials(@TempDir Path mount) throws Exception {
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:absent", null, false));
    writeSecret(mount, "blank", "   \n");
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:blank", null, false));
    writeSecret(mount, "injected", "Bearer good\r\nX-Injected: value");
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:injected", null, false));
  }

  @Test
  void resolvesAMountedMtlsIdentityWhenTheProfileRequiresIt(@TempDir Path mount) throws Exception {
    writeSecret(mount, "provider-a", "Bearer mounted-token");
    writeKeyStore(mount, "provider-a-mtls", "keystore-password");
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    var material = resolver.resolve("mount:provider-a", "mount:provider-a-mtls", true);
    assertEquals("Bearer mounted-token", material.authorizationHeader());
    assertNotNull(material.sslContext());
  }

  @Test
  void failsClosedWhenARequiredMtlsIdentityIsAbsentOrUnusable(@TempDir Path mount) throws Exception {
    writeSecret(mount, "provider-a", "Bearer mounted-token");
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:provider-a", "mount:absent-mtls", true));
    writeKeyStore(mount, "provider-b-mtls", "keystore-password");
    writeSecret(mount, "provider-b-mtls.p12.pass", "wrong-password");
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:provider-a", "mount:provider-b-mtls", true));
  }

  @Test
  void neverPlacesSecretMaterialInAFailureMessage(@TempDir Path mount) throws Exception {
    Path file = writeSecret(mount, "provider-a", "Bearer super-secret-value");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
    var resolver = new MountedSecretProviderCredentialResolver(mount);
    var failure = assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:provider-a", null, false));
    assertTrue(failure.getMessage() != null && !failure.getMessage().contains("super-secret-value"), failure.getMessage());
  }

  @Test
  void theFailClosedResolverRemainsTheDefaultWithoutAConfiguredMount() {
    var resolver = new FailClosedProviderCredentialResolver();
    assertThrows(IllegalStateException.class, () -> resolver.resolve("mount:provider-a", null, false));
  }
}
