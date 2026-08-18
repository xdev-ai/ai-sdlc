package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every field the portal reads from a list response has to exist in that response.
 *
 * <p>The run list and the run picker both render {@code idempotencyKey}, and the list contract did not carry it — only
 * the detail contract did. Bracket access on a map without the key yields null rather than an error, so nothing failed
 * loudly: the list simply showed a blank key on every row, and every option in the picker read {@code "PASSED · "}
 * with nothing after the separator, which made the runs indistinguishable in the one control built for telling them
 * apart. This test reads the template and the contract and refuses that combination.
 */
class ValidationRunListShapeTest {
  private static final Path TEMPLATE = Path.of("..", "portal", "src", "main", "resources", "templates", "app.html");
  private static final Path CONTRACTS = Path.of("src", "main", "java", "ai", "xdev", "aisdlc", "web", "ValidationContracts.java");

  @Test
  void everyFieldTheRunListTemplateReadsExistsOnTheListContract() throws IOException {
    if (!Files.exists(TEMPLATE)) return; // the portal is a sibling module; skip if the layout ever changes
    String template = Files.readString(TEMPLATE);
    String contracts = Files.readString(CONTRACTS);

    String listRecord = recordBody(contracts, "ValidationRunListItem");
    assertTrue(listRecord != null && !listRecord.isBlank(), "ValidationRunListItem was not found");

    // Extracted from the validations section only. Scanning the whole file and filtering by substring produced
    // false positives from unrelated sections — `.name` matches `_csrf.parameterName`, and `slug` appears on the
    // projects table — which is how the first version of this test failed on fields the view never reads.
    // Anchored on the section element, not the string: `page == 'validations'` also appears in the sidebar link, and
    // starting there swept in the header and the scope selector, whose items are organizations and projects.
    String section = between(template, "<section th:if=\"${page == 'validations'}\"", "</section>");
    List<String> read = new ArrayList<>();
    Matcher bracket = Pattern.compile("\\$\\{(?:item|run)\\['(\\w+)'\\]").matcher(section);
    while (bracket.find()) read.add(bracket.group(1));
    Matcher dotted = Pattern.compile("\\$\\{(?:item|run)\\.(\\w+)").matcher(section);
    while (dotted.find()) read.add(dotted.group(1));
    assertTrue(!read.isEmpty(), "the validations section reads no item fields at all, so this test proves nothing");

    List<String> missing = new ArrayList<>();
    for (String field : read.stream().distinct().toList()) {
      if (!listRecord.contains(" " + field)) missing.add(field);
    }
    assertTrue(missing.isEmpty(),
        "the validations view reads fields the list contract does not provide, so they render blank: " + missing);
  }

  private static String recordBody(String source, String name) {
    Matcher matcher = Pattern.compile("record " + name + "\\(([^)]*)\\)").matcher(source);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String between(String source, String from, String to) {
    int start = source.indexOf(from);
    if (start < 0) return "";
    int end = source.indexOf(to, start);
    return end < 0 ? source.substring(start) : source.substring(start, end);
  }
}
