package ai.xdev.aisdlc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical form of an audit payload, used on both the append and the verify path.
 *
 * <p>The audit chain hashes the payload. The payload column is {@code jsonb}, and PostgreSQL normalises jsonb on
 * write: it reorders object members and drops insignificant whitespace, so the text read back is not the text that
 * was written. Hashing the raw string therefore produced a chain that could never verify — the append hashed
 * {@code {"slug":"x"}} and the verification hashed {@code {"slug": "x"}}.
 *
 * <p>Canonicalising on the parsed value removes the problem at its source: the hash is defined over what the JSON
 * <em>means</em>, not how a particular layer chose to print it. A payload that is not valid JSON is hashed verbatim,
 * so a malformed value still chains rather than being silently dropped.
 */
public final class AuditPayloadCanonicalizer {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AuditPayloadCanonicalizer() {}

  /** Returns the canonical text to hash. Null or blank input becomes the empty object, matching the column default. */
  public static String canonical(String payload) {
    if (payload == null || payload.isBlank()) return "{}";
    try {
      return render(MAPPER.readTree(payload));
    } catch (Exception notJson) {
      return payload;
    }
  }

  private static String render(JsonNode node) {
    if (node == null || node.isNull()) return "null";
    if (node.isObject()) {
      Map<String, JsonNode> ordered = new TreeMap<>();
      node.fieldNames().forEachRemaining(name -> ordered.put(name, node.get(name)));
      StringBuilder builder = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<String, JsonNode> field : ordered.entrySet()) {
        if (!first) builder.append(',');
        builder.append(com.fasterxml.jackson.databind.node.TextNode.valueOf(field.getKey()))
            .append(':').append(render(field.getValue()));
        first = false;
      }
      return builder.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder builder = new StringBuilder("[");
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) builder.append(',');
        builder.append(render(node.get(index)));
      }
      return builder.append(']').toString();
    }
    return node.toString();
  }
}
