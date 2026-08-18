package ai.xdev.aisdlc.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a documentation page's Markdown as HTML that cannot contain anything the author did not intend.
 *
 * <p><b>The safety argument, because this is the one place in the portal where user-authored text becomes markup.</b>
 * The order of operations is what makes it safe: every character is HTML-escaped <em>first</em>, and only then are
 * tags added around the already-escaped text. Nothing in a page body can therefore produce an element or an
 * attribute — a body containing {@code <script>} arrives at this class as text and leaves as {@code &lt;script&gt;}
 * wrapped in a paragraph. There is no path in which author input is un-escaped.
 *
 * <p>The supported subset is deliberately tiny, and every omission is a removed attack surface:
 *
 * <ul>
 *   <li>headings {@code #}–{@code ####}, emitted as {@code h3}–{@code h6} so a page cannot outrank the page title
 *   <li>paragraphs, bullet lists, numbered lists
 *   <li>{@code **bold**} and {@code `code`}
 *   <li>fenced code blocks
 * </ul>
 *
 * <p><b>No links and no images.</b> Supporting them would mean accepting a URL from the author, and a URL is where
 * {@code javascript:} and {@code data:} live. A reader who needs the URL can still see it: it stays visible as text.
 *
 * <p>Before this existed the reader showed the raw source in a monospace block. That was safe but barely readable —
 * a document full of visible {@code ##} markers is a text dump, not a document — and this recovers the reading
 * experience without giving up the guarantee.
 */
final class MarkdownToSafeHtml {
  /** Same reason the chunker has a ceiling: a runaway body should be refused, not rendered into a megabyte of DOM. */
  static final int MAX_INPUT_CHARS = 400_000;

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
  private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
  private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
  private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,}).*$");
  /** Applied to escaped text, so the delimiters are all that can match. */
  private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
  private static final Pattern CODE = Pattern.compile("`([^`]+)`");

  private MarkdownToSafeHtml() {}

  static String render(String markdown) {
    if (markdown == null || markdown.isBlank()) return "";
    String source = markdown.length() > MAX_INPUT_CHARS ? markdown.substring(0, MAX_INPUT_CHARS) : markdown;

    StringBuilder html = new StringBuilder();
    List<String> paragraph = new ArrayList<>();
    List<String> listItems = new ArrayList<>();
    boolean ordered = false;
    boolean inFence = false;
    StringBuilder fenced = new StringBuilder();

    for (String rawLine : source.split("\n", -1)) {
      String line = rawLine.stripTrailing();

      if (inFence) {
        if (FENCE.matcher(line).matches()) {
          html.append("<pre class=\"doc-code\"><code>").append(fenced).append("</code></pre>");
          fenced.setLength(0);
          inFence = false;
        } else {
          fenced.append(escape(rawLine)).append('\n');
        }
        continue;
      }
      if (FENCE.matcher(line).matches()) {
        flushParagraph(html, paragraph);
        flushList(html, listItems, ordered);
        inFence = true;
        continue;
      }

      if (line.isBlank()) {
        flushParagraph(html, paragraph);
        flushList(html, listItems, ordered);
        continue;
      }

      Matcher heading = HEADING.matcher(line);
      if (heading.matches()) {
        flushParagraph(html, paragraph);
        flushList(html, listItems, ordered);
        // h1 and h2 in a body become h3: the page title is the h2 of the panel, and a body must not outrank it.
        int level = Math.min(6, Math.max(3, heading.group(1).length() + 2));
        html.append("<h").append(level).append('>').append(inline(escape(heading.group(2).strip())))
            .append("</h").append(level).append('>');
        continue;
      }

      Matcher bullet = BULLET.matcher(line);
      Matcher numbered = NUMBERED.matcher(line);
      if (bullet.matches() || numbered.matches()) {
        boolean nowOrdered = numbered.matches() && !bullet.matches();
        if (!listItems.isEmpty() && nowOrdered != ordered) flushList(html, listItems, ordered);
        flushParagraph(html, paragraph);
        ordered = nowOrdered;
        listItems.add(inline(escape((nowOrdered ? numbered.group(1) : bullet.group(1)).strip())));
        continue;
      }

      flushList(html, listItems, ordered);
      paragraph.add(inline(escape(line.strip())));
    }

    // An unterminated fence still has to keep its text rather than dropping the tail.
    if (inFence && fenced.length() > 0) {
      html.append("<pre class=\"doc-code\"><code>").append(fenced).append("</code></pre>");
    }
    flushParagraph(html, paragraph);
    flushList(html, listItems, ordered);
    return html.toString();
  }

  private static void flushParagraph(StringBuilder html, List<String> paragraph) {
    if (paragraph.isEmpty()) return;
    html.append("<p>").append(String.join(" ", paragraph)).append("</p>");
    paragraph.clear();
  }

  private static void flushList(StringBuilder html, List<String> items, boolean ordered) {
    if (items.isEmpty()) return;
    String tag = ordered ? "ol" : "ul";
    html.append('<').append(tag).append('>');
    items.forEach(item -> html.append("<li>").append(item).append("</li>"));
    html.append("</").append(tag).append('>');
    items.clear();
  }

  /** Escapes everything that could start a tag, an attribute, or an entity. Runs before any tag is added. */
  private static String escape(String text) {
    StringBuilder escaped = new StringBuilder(text.length() + 16);
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }

  /**
   * Inline emphasis over already-escaped text.
   *
   * <p>Only the literal delimiters can match, because every angle bracket, quote and ampersand is already an entity.
   * The replacement is a fixed tag with no attributes, and {@link Matcher#quoteReplacement} keeps a {@code $} in the
   * author's text from being read as a group reference — the same class of mistake that corrupted a source file
   * earlier in this project's history.
   */
  private static String inline(String escaped) {
    String result = replaceAll(BOLD, escaped, "<strong>", "</strong>");
    return replaceAll(CODE, result, "<code>", "</code>");
  }

  private static String replaceAll(Pattern pattern, String text, String open, String close) {
    Matcher matcher = pattern.matcher(text);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(result, Matcher.quoteReplacement(open + matcher.group(1) + close));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
