package ai.xdev.aisdlc.service;

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
 * Endpoints that hand back a raw {@code Map} return whatever the SQL called the column, so an un-aliased
 * {@code snake_case} column becomes a {@code snake_case} JSON field while every record-based endpoint returns
 * camelCase. That split is not cosmetic.
 *
 * <p>The Spec Kit registry page read {@code item.lifecycleStatus} while the API returned {@code lifecycle_status}, and
 * Thymeleaf's {@code MapAccessor} raises on a missing key rather than yielding null — so the page returned <b>500 as
 * soon as a single kit existed</b>, which is step 5 of the setup sequence. The traceability graph and the quality
 * fragment had the mirror-image arrangement, reading snake_case that happened to match.
 *
 * <p>This keeps the two sides from drifting again by refusing an un-aliased snake_case column in any query whose rows
 * are returned to a client. Queries whose rows are consumed in Java are exempt, and named here explicitly rather than
 * detected, because renaming those would break the code that reads them by key.
 */
class ClientFacingNamingTest {
  private static final Path SERVICES = Path.of("src", "main", "java", "ai", "xdev", "aisdlc", "service");

  /**
   * Services whose rows are consumed in Java rather than returned, so their column names are SQL identifiers and
   * should stay snake_case to match the schema they are read against.
   *
   * <p>Each was checked two ways: its controller returns typed records only, and its rows are read by
   * {@code .get("some_column")} inside the service. Renaming those would need both sides moved together for no
   * observable gain, and string keys give the compiler nothing to catch if one side is missed.
   *
   * <p>The exemption is per service, which is its weakness: {@code RiskIntelligenceService} sat on this list while
   * one of its queries was embedded verbatim in an API response as {@code sourceSummary.latestQuality}, hidden inside
   * a {@code Map} field of an otherwise typed record and invisible whenever no quality period had been recorded. It
   * is no longer exempt. Before adding a service here, check whether any response record carries a {@code Map} that a
   * row is placed into, and populate it before concluding the response is clean.
   */
  private static final List<String> INTERNAL_READERS = List.of(
      "AgentRulesService.java", "RuntimeAiBrokerService.java",
      "RuntimeAiToolBrokerService.java", "KnowledgeBaseService.java", "PolicyEvaluationService.java",
      "RuntimeAiGovernanceService.java", "RuntimeAiProviderProxyService.java", "BudgetEnforcementService.java",
      "InferenceCostService.java");

  @Test
  void noClientFacingQueryReturnsAnUnAliasedSnakeCaseColumn() throws IOException {
    List<String> problems = new ArrayList<>();
    try (var files = Files.list(SERVICES)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
        if (INTERNAL_READERS.contains(file.getFileName().toString())) continue;
        String source = Files.readString(file);
        // The Java literal contains escaped quotes once a column is aliased (as \\"nodeType\\"), so a pattern that
        // stops at the first quote reads only the head of the statement and misses every column after it — which made
        // the first version of this guard pass while the alias it was written for had been removed.
        // Both string forms. A text block is the natural way to write a long query, and scanning only quoted
        // literals meant a snake_case column inside one sailed past unnoticed — four services here use text blocks.
        Matcher queries = Pattern.compile(
            "\"\"\"(.*?)\"\"\"|\"(select(?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL).matcher(source);
        while (queries.find()) {
          String select = queries.group(1) != null ? queries.group(1) : queries.group(2);
          if (select == null || !select.strip().toLowerCase().startsWith("select")) continue;
          int from = select.toLowerCase().indexOf(" from ");
          if (from < 0) continue;
          for (String column : select.substring("select".length(), from).split(",")) {
            String trimmed = column.trim();
            if (trimmed.toLowerCase().contains(" as ") || trimmed.contains("(")) continue;
            String bare = trimmed.contains(".") ? trimmed.substring(trimmed.lastIndexOf('.') + 1) : trimmed;
            if (bare.matches("[a-z][a-z0-9]*(_[a-z0-9]+)+")) {
              problems.add(file.getFileName() + ": " + bare + " would reach the client as snake_case; alias it, "
                  + "or add the service to INTERNAL_READERS if its rows are consumed in Java");
            }
          }
        }
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n  ", problems));
  }
}
