package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The quality row inside a risk snapshot is both returned to clients and read in Java by key, and the two sides are
 * joined by nothing but matching strings.
 *
 * <p>That is the dangerous shape: if an alias and a {@code quality.get(...)} key drift apart, nothing fails to
 * compile, no request errors, and {@code qualityRisk} quietly computes from zeros — a risk score that looks
 * calculated and is not. This pins them to each other.
 */
class RiskQualityKeyPairingTest {
  private static final Path SERVICE =
      Path.of("src", "main", "java", "ai", "xdev", "aisdlc", "service", "RiskIntelligenceService.java");

  @Test
  void everyAliasInTheQualityQueryIsTheKeyTheScoreReads() throws IOException {
    String source = Files.readString(SERVICE);

    // Escaped quotes, because the aliases put \\" inside the Java literal: a pattern stopping at the first quote
    // reads only the head of the statement. That mistake has now cost three attempts in this codebase.
    Matcher select = Pattern.compile("select ((?:[^\"\\\\]|\\\\.)*?) from quality_metric_snapshots").matcher(source);
    assertTrue(select.find(), "the latest-quality query was not found");
    TreeSet<String> aliases = new TreeSet<>();
    Matcher alias = Pattern.compile("as \\\\\"(\\w+)\\\\\"").matcher(select.group(1));
    while (alias.find()) aliases.add(alias.group(1));
    assertEquals(9, aliases.size(), "expected nine aliased metric columns, found " + aliases);

    TreeSet<String> read = new TreeSet<>();
    Matcher reads = Pattern.compile("quality\\.get\\(\"(\\w+)\"\\)").matcher(source);
    while (reads.find()) read.add(reads.group(1));
    assertTrue(read.size() >= 7, "expected the score to read at least seven metrics, found " + read);

    TreeSet<String> unreadable = new TreeSet<>(read);
    unreadable.removeAll(aliases);
    assertTrue(unreadable.isEmpty(),
        "the score reads keys the query does not produce, so qualityRisk would compute from zeros: " + unreadable);
  }

  /** A snake_case key left in either place means one side was changed without the other. */
  @Test void neitherSideStillUsesSnakeCase() throws IOException {
    String source = Files.readString(SERVICE);

    Matcher reads = Pattern.compile("quality\\.get\\(\"([a-z0-9_]+)\"\\)").matcher(source);
    while (reads.find()) {
      assertTrue(!reads.group(1).contains("_"),
          "the score still reads a snake_case key: " + reads.group(1));
    }
  }
}
