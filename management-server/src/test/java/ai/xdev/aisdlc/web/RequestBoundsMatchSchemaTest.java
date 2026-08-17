package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A declared request bound must not exceed the column it is written into.
 *
 * <p>Seven fields across five request records accepted more than the database would store. A caller inside the
 * documented limit therefore got a {@code 500} — the insert failed after validation passed — instead of a {@code 400}
 * naming the field. Registering a Spec Kit version longer than 80 characters was the case that surfaced it, while
 * {@code @Size(max = 160)} said 160 was fine.
 *
 * <p>This checks the whole class rather than those seven fields, by reading the real column widths out of the Flyway
 * migrations and comparing them to the annotations. It runs without a database, which is the point: the mismatch is
 * visible in the source, and the previous defects of this shape were only ever found by calling the running API.
 */
class RequestBoundsMatchSchemaTest {
  /** request record field -> the table and column it is persisted into. */
  private static final Map<String, String> FIELD_TO_COLUMN = new LinkedHashMap<>();

  static {
    FIELD_TO_COLUMN.put("KitInput.version", "spec_kits.version");
    FIELD_TO_COLUMN.put("PolicyInput.version", "policies.version");
    FIELD_TO_COLUMN.put("ConstitutionInput.version", "constitutions.version");
    FIELD_TO_COLUMN.put("CapabilityInput.subject", "capability_grants.subject");
    FIELD_TO_COLUMN.put("TraceNodeInput.externalKey", "trace_nodes.external_key");
    FIELD_TO_COLUMN.put("TraceNodeInput.label", "trace_nodes.label");
    FIELD_TO_COLUMN.put("TraceNodeInput.status", "trace_nodes.status");
    FIELD_TO_COLUMN.put("TraceEdgeInput.relation", "trace_edges.relation");
    FIELD_TO_COLUMN.put("ReviewInput.title", "review_items.title");
  }

  @Test
  void noDeclaredRequestBoundExceedsItsColumn() throws IOException {
    Map<String, Integer> declared = declaredSizes();
    Map<String, Integer> columns = columnWidths();
    List<String> problems = new ArrayList<>();

    FIELD_TO_COLUMN.forEach((field, column) -> {
      Integer bound = declared.get(field);
      Integer width = columns.get(column);
      if (bound == null) {
        problems.add(field + " has no @Size bound; add one or remove it from this map");
      } else if (width == null) {
        problems.add(column + " was not found in any migration; the mapping is stale");
      } else if (bound > width) {
        problems.add(field + " accepts " + bound + " characters but " + column + " stores " + width
            + " — a caller inside the documented limit would get a 500, not a 400");
      }
    });

    assertTrue(problems.isEmpty(), String.join("\n  ", problems));
  }

  /** {@code @Size(max = N) String field} inside each {@code record Name(...)} of the governance controller. */
  private static Map<String, Integer> declaredSizes() throws IOException {
    String source = Files.readString(Path.of("src/main/java/ai/xdev/aisdlc/web/GovernanceController.java"));
    Map<String, Integer> sizes = new LinkedHashMap<>();
    Matcher records = Pattern.compile("record (\\w+)\\(([^;]*?)\\) \\{\\}", Pattern.DOTALL).matcher(source);
    while (records.find()) {
      String record = records.group(1);
      Matcher fields = Pattern.compile("@Size\\(max = (\\d+)\\)\\s+String (\\w+)").matcher(records.group(2));
      while (fields.find()) sizes.put(record + "." + fields.group(2), Integer.parseInt(fields.group(1)));
    }
    return sizes;
  }

  /**
   * {@code table.column -> width}, taken from every migration so a later {@code alter column ... type varchar(n)}
   * wins over the original {@code create table}.
   */
  private static Map<String, Integer> columnWidths() throws IOException {
    Map<String, Integer> widths = new LinkedHashMap<>();
    List<Path> migrations;
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
      migrations = files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
    }
    Pattern createTable = Pattern.compile("create table (\\w+)\\s*\\((.*?)\\n\\);",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    // V1 declares several columns per line, comma separated, so this cannot anchor to the start of a line.
    Pattern column = Pattern.compile("(?:^|,)\\s*(\\w+)\\s+(?:varchar|char)\\((\\d+)\\)",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    Pattern alterType = Pattern.compile(
        "alter table (\\w+)\\s+alter column (\\w+)\\s+type\\s+(?:varchar|char)\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    for (Path migration : migrations) {
      String sql = Files.readString(migration);
      Matcher tables = createTable.matcher(sql);
      while (tables.find()) {
        String table = tables.group(1).toLowerCase();
        Matcher cols = column.matcher(tables.group(2));
        while (cols.find()) widths.put(table + "." + cols.group(1).toLowerCase(), Integer.parseInt(cols.group(2)));
      }
      Matcher alters = alterType.matcher(sql);
      while (alters.find()) {
        widths.put(alters.group(1).toLowerCase() + "." + alters.group(2).toLowerCase(), Integer.parseInt(alters.group(3)));
      }
    }
    return widths;
  }
}
