package ai.xdev.aisdlc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A SHA-256 written in uppercase hex is the same digest as one written in lowercase, and a client that formats with
 * {@code %X} sends uppercase. Every controller in this module publishes {@code ^[a-fA-F0-9]{64}$}, so uppercase is part
 * of the API contract.
 *
 * <p>The defect this guards actually happened: {@code AgentGovernanceService} carried a lowercase-only copy of that
 * pattern, so the controller accepted an uppercase fingerprint and the service then rejected it with "Session
 * fingerprint is invalid" — telling a caller its valid digest was malformed. The service's own
 * {@code toLowerCase(Locale.ROOT)} normalisation was unreachable for exactly the input it existed to normalise. No unit
 * test caught it because each layer was tested with a lowercase literal.
 *
 * <p>So the assertion is on the source itself, not on behaviour: a lowercase-only 64-hex pattern anywhere in main is
 * the bug, wherever it appears next.
 */
class DigestCaseConsistencyTest {

  private static final Path MAIN = Path.of("src/main/java");
  // Matches a 64-hex validation pattern that omits A-F, in either a Bean Validation annotation or a plain matches().
  private static final Pattern LOWERCASE_ONLY = Pattern.compile("\\[a-f0-9\\]\\{64\\}");

  @Test
  @DisplayName("no digest validator rejects the uppercase hex the controllers accept")
  void noLowercaseOnlyDigestPattern() throws IOException {
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        Matcher matcher = LOWERCASE_ONLY.matcher(source);
        while (matcher.find()) {
          offenders.add(file + " → " + source.substring(matcher.start(), matcher.end()));
        }
      }
    }
    assertTrue(offenders.isEmpty(),
        "These digest validators reject uppercase hex, which the controllers accept and which is a valid SHA-256. "
            + "A caller formatting digests with %X gets a 400 saying its digest is invalid: " + offenders);
  }

  @Test
  @DisplayName("the guard is reading real source, not an empty directory")
  void theGuardActuallyScansSomething() throws IOException {
    // Without this, a wrong MAIN path would make the assertion above pass by finding no files at all.
    long scanned;
    try (Stream<Path> files = Files.walk(MAIN)) {
      scanned = files.filter(p -> p.toString().endsWith(".java")).count();
    }
    assertTrue(scanned > 50, "expected to scan the whole main source tree, scanned " + scanned + " files");

    long withDigestPatterns;
    try (Stream<Path> files = Files.walk(MAIN)) {
      withDigestPatterns = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
        try {
          return Files.readString(p).contains("[a-fA-F0-9]{64}");
        } catch (IOException e) {
          return false;
        }
      }).count();
    }
    assertTrue(withDigestPatterns >= 5,
        "expected several files to carry the uppercase-tolerant pattern, found " + withDigestPatterns);
  }
}
