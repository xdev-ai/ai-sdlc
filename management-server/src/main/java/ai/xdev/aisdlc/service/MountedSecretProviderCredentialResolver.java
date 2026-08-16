package ai.xdev.aisdlc.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Resolves provider secrets from a read-only secret-manager mount.
 *
 * <p>This is the provider-neutral delivery mechanism every approved secret manager already supports: a Vault Agent, a
 * secrets-store CSI driver, or a Kubernetes Secret all project material into a read-only directory. Replacing this with
 * a direct secret-manager SDK is one implementation of {@link ProviderCredentialResolver} and needs no other change.
 *
 * <p>The resolver is audited rather than opportunistic. It is created only when an operator configures a mount path, it
 * accepts only {@code mount:<name>} references that cannot escape the mount, it refuses material that is readable by
 * group or others, and it never logs, returns, or wraps a secret value in an exception message.
 */
public class MountedSecretProviderCredentialResolver implements ProviderCredentialResolver {
  private static final String REFERENCE_PREFIX = "mount:";
  private static final String REFERENCE_NAME = "[a-z0-9][a-z0-9._-]{0,62}";
  private static final long MAX_AUTHORIZATION_BYTES = 8_192L;
  private static final long MAX_KEYSTORE_BYTES = 262_144L;
  private static final Set<PosixFilePermission> FORBIDDEN_PERMISSIONS = Set.of(
      PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
      PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

  private final Path mountPath;

  public MountedSecretProviderCredentialResolver(Path mountPath) {
    this.mountPath = mountPath.toAbsolutePath().normalize();
  }

  @Override
  public CredentialMaterial resolve(String credentialReference, String mtlsReference, boolean requireMtls) {
    String authorization = readAuthorization(resolveReference(credentialReference, ""));
    SSLContext sslContext = requireMtls ? readKeyStore(mtlsReference) : null;
    return new CredentialMaterial(authorization, sslContext);
  }

  private Path resolveReference(String reference, String suffix) {
    if (reference == null || !reference.startsWith(REFERENCE_PREFIX)) {
      throw new IllegalStateException("Provider secret reference is not a mounted reference");
    }
    String name = reference.substring(REFERENCE_PREFIX.length());
    if (!name.matches(REFERENCE_NAME) || name.contains("..")) {
      throw new IllegalStateException("Provider secret reference is not a permitted name");
    }
    Path candidate = mountPath.resolve(name + suffix).toAbsolutePath().normalize();
    if (!candidate.startsWith(mountPath)) {
      throw new IllegalStateException("Provider secret reference resolved outside the configured mount");
    }
    return candidate;
  }

  private static void requireProtectedRegularFile(Path path, long maxBytes) {
    try {
      if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Provider secret material is not a protected regular file");
      }
      if (Files.size(path) <= 0 || Files.size(path) > maxBytes) {
        throw new IllegalStateException("Provider secret material has an unacceptable size");
      }
      // A POSIX-less filesystem cannot prove the mount is private, so the check is skipped rather than faked.
      if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (permissions.stream().anyMatch(FORBIDDEN_PERMISSIONS::contains)) {
          throw new IllegalStateException("Provider secret material is readable beyond its owner");
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Provider secret material is unreadable");
    }
  }

  private static String readAuthorization(Path path) {
    requireProtectedRegularFile(path, MAX_AUTHORIZATION_BYTES);
    String value;
    try {
      value = Files.readString(path, StandardCharsets.UTF_8).strip();
    } catch (IOException exception) {
      throw new IllegalStateException("Provider credential is unreadable");
    }
    if (value.isBlank() || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
      throw new IllegalStateException("Provider credential is not a usable header value");
    }
    return value;
  }

  private SSLContext readKeyStore(String mtlsReference) {
    Path keyStorePath = resolveReference(mtlsReference, ".p12");
    Path passwordPath = resolveReference(mtlsReference, ".p12.pass");
    requireProtectedRegularFile(keyStorePath, MAX_KEYSTORE_BYTES);
    requireProtectedRegularFile(passwordPath, MAX_AUTHORIZATION_BYTES);
    char[] password = null;
    try {
      password = Files.readString(passwordPath, StandardCharsets.UTF_8).strip().toCharArray();
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      try (InputStream stream = Files.newInputStream(keyStorePath)) {
        keyStore.load(stream, password);
      }
      KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagers.init(keyStore, password);
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(keyManagers.getKeyManagers(), null, null);
      return sslContext;
    } catch (Exception exception) {
      throw new IllegalStateException("Provider mTLS identity is unavailable");
    } finally {
      if (password != null) Arrays.fill(password, '\0');
    }
  }
}
