package ai.xdev.aisdlc.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a Markdown page into the units an AI is actually handed.
 *
 * <p>A whole document is the wrong unit for retrieval: it does not fit a prompt, and an answer grounded in it can
 * only cite the file. A fixed-size window is also the wrong unit, because it cuts sentences in half and loses the
 * heading that says what the text is about. So sections are the unit — split at Markdown headings, each chunk
 * labelled with the heading path that leads to it, so an answer can cite {@code "Intake > Insurance check"}.
 *
 * <p>Pure and deterministic: same input, same chunks, same order. That is what makes it testable without a
 * database, and it is why the digest of a chunk is a usable identity.
 *
 * <p>Two rules exist because of specific ways this goes wrong:
 *
 * <ul>
 *   <li>A {@code #} inside a fenced code block is a shell comment or a C preprocessor directive, not a heading. A
 *       line-oriented pass that ignores fences will split a code sample in half at a comment and file the second
 *       half under an invented section. This chunker tracks fences and treats a fenced block as indivisible, so no
 *       chunk can ever end with an unclosed fence.
 *   <li>Oversized sections are packed at block boundaries, never mid-sentence. A section longer than the limit is
 *       divided between paragraphs; a single paragraph longer than the limit is divided between lines; only a
 *       single line longer than the limit is cut, and then at the character, because there is nothing left to
 *       split on.
 * </ul>
 *
 * <p>The size limit therefore has one documented exception: a fenced block larger than the limit is emitted whole,
 * in its own chunk. The alternative was to close the fence at each cut and reopen it on the next piece, which keeps
 * every chunk within budget but makes the chunk text no longer a verbatim extract of the page — and a verbatim
 * extract is what lets a reader check a citation. Half a code sample is worthless to a model anyway. Callers that
 * budget a prompt must therefore measure the chunks they received rather than assume the limit; a block beyond
 * {@link #MAX_FENCE_CHARS} is refused outright rather than silently blowing that budget.
 */
public final class MarkdownChunker {
  /**
   * Roughly 300 tokens of English or 200 of Vietnamese: small enough that several fit a prompt alongside the
   * question, large enough that a section survives intact rather than arriving as fragments.
   */
  public static final int DEFAULT_MAX_CHARS = 1_200;

  /** {@code knowledge_chunks.heading_path} is varchar(600). Truncation happens here rather than at the driver. */
  static final int MAX_HEADING_PATH = 600;

  /**
   * A body producing more chunks than this is not documentation being indexed, it is a runaway loop or a machine
   * dump. Refusing is better than writing a million rows and discovering the cost at query time.
   */
  static final int MAX_CHUNKS = 2_000;

  /**
   * A fenced block is never divided, so it sets its own chunk size. Past this it is not a code sample in a document,
   * it is a data file pasted into one, and it would silently exceed any prompt budget the caller planned.
   */
  static final int MAX_FENCE_CHARS = 40_000;

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \t]+(.*?)[ \t]*#*[ \t]*$");
  private static final Pattern FENCE = Pattern.compile("^[ \t]{0,3}(`{3,}|~{3,})(.*)$");
  private static final String SEPARATOR = " > ";

  private final int maxChars;

  public MarkdownChunker() { this(DEFAULT_MAX_CHARS); }

  public MarkdownChunker(int maxChars) {
    if (maxChars < 200 || maxChars > 20_000) throw new IllegalArgumentException("chunk size must be between 200 and 20000 characters");
    this.maxChars = maxChars;
  }

  /** One retrieval unit: where it sits in the document, and the text itself. */
  public record Chunk(int ordinal, String headingPath, String content) {}

  /**
   * @param rootHeading the page title, used as the heading path for text that appears before any heading — and as
   *     the whole path for a body with no headings at all, which is common for a short note
   */
  public List<Chunk> chunk(String rootHeading, String markdown) {
    String root = rootHeading == null || rootHeading.isBlank() ? "(untitled)" : rootHeading.strip();
    if (markdown == null || markdown.isBlank()) return List.of();

    List<Chunk> chunks = new ArrayList<>();
    Deque<String> headings = new ArrayDeque<>();
    List<Block> blocks = new ArrayList<>();
    List<String> block = new ArrayList<>();
    String fence = null;

    for (String line : markdown.split("\n", -1)) {
      if (fence != null) {
        block.add(line);
        Matcher closing = FENCE.matcher(line);
        // A fence closes on a run of the same character at least as long as the one that opened it, with nothing
        // but whitespace after — anything else is content that merely starts with backticks.
        if (closing.matches() && closing.group(1).charAt(0) == fence.charAt(0)
            && closing.group(1).length() >= fence.length() && closing.group(2).isBlank()) {
          fence = null;
          blocks.add(new Block(true, List.copyOf(block)));
          block = new ArrayList<>();
        }
        continue;
      }

      Matcher opening = FENCE.matcher(line);
      if (opening.matches()) {
        if (!block.isEmpty()) { blocks.add(new Block(false, List.copyOf(block))); block = new ArrayList<>(); }
        fence = opening.group(1);
        block.add(line);
        continue;
      }

      Matcher heading = HEADING.matcher(line);
      if (heading.matches()) {
        if (!block.isEmpty()) { blocks.add(new Block(false, List.copyOf(block))); block = new ArrayList<>(); }
        emit(chunks, path(root, headings), blocks);
        blocks = new ArrayList<>();
        int level = heading.group(1).length();
        String title = heading.group(2).strip();
        while (headings.size() >= level) headings.removeLast();
        // A jump from h2 straight to h5 leaves gaps. Padding keeps the path contiguous so the deepest heading is
        // always last, which is what a citation reads.
        while (headings.size() < level - 1) headings.addLast("");
        headings.addLast(title.isEmpty() ? "(untitled section)" : title);
        continue;
      }

      if (line.isBlank()) {
        if (!block.isEmpty()) { blocks.add(new Block(false, List.copyOf(block))); block = new ArrayList<>(); }
      } else {
        block.add(line);
      }
    }

    // An unterminated fence is malformed Markdown, not a reason to lose the text. Keep it as its own block, and
    // treat it as fenced: it opened one, so cutting it would still leave an unbalanced piece.
    if (!block.isEmpty()) blocks.add(new Block(fence != null, List.copyOf(block)));
    emit(chunks, path(root, headings), blocks);
    return List.copyOf(chunks);
  }

  /** One paragraph, or one fenced code block. Fenced blocks are indivisible; paragraphs are not. */
  private record Block(boolean fenced, List<String> lines) {}

  private void emit(List<Chunk> chunks, String headingPath, List<Block> blocks) {
    List<Block> texts = new ArrayList<>();
    for (Block block : blocks) {
      if (!String.join("\n", block.lines()).isBlank()) texts.add(block);
    }
    // A heading with no body under it is not a retrieval unit; its path is carried by the chunks beneath it.
    if (texts.isEmpty()) return;

    StringBuilder packed = new StringBuilder();
    for (Block block : texts) {
      String text = String.join("\n", block.lines()).strip();
      for (String piece : block.fenced() ? whole(text) : divide(text)) {
        int joined = packed.isEmpty() ? piece.length() : packed.length() + 2 + piece.length();
        if (!packed.isEmpty() && joined > maxChars) {
          add(chunks, headingPath, packed.toString());
          packed.setLength(0);
        }
        if (!packed.isEmpty()) packed.append("\n\n");
        packed.append(piece);
      }
    }
    if (!packed.isEmpty()) add(chunks, headingPath, packed.toString());
  }

  /**
   * A fenced block, kept intact. Cutting it would produce a chunk ending in an unclosed fence and a following chunk
   * that opens with code the reader has no way to recognise as code.
   */
  private List<String> whole(String text) {
    if (text.length() > MAX_FENCE_CHARS) {
      throw new IllegalArgumentException("A fenced block of " + text.length() + " characters exceeds the "
          + MAX_FENCE_CHARS + "-character limit; fenced blocks are never split, so move this content to an attachment");
    }
    return List.of(text);
  }

  /** Breaks one block down to pieces within the limit, giving up as little structure as possible at each step. */
  private List<String> divide(String text) {
    if (text.length() <= maxChars) return List.of(text);

    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (line.length() > maxChars) {
        if (!current.isEmpty()) { pieces.add(current.toString()); current.setLength(0); }
        for (int start = 0; start < line.length(); start += maxChars) {
          pieces.add(line.substring(start, Math.min(line.length(), start + maxChars)));
        }
        continue;
      }
      int joined = current.isEmpty() ? line.length() : current.length() + 1 + line.length();
      if (!current.isEmpty() && joined > maxChars) { pieces.add(current.toString()); current.setLength(0); }
      if (!current.isEmpty()) current.append('\n');
      current.append(line);
    }
    if (!current.isEmpty()) pieces.add(current.toString());
    return pieces;
  }

  private void add(List<Chunk> chunks, String headingPath, String content) {
    if (chunks.size() >= MAX_CHUNKS) {
      throw new IllegalArgumentException("Page body produces more than " + MAX_CHUNKS + " chunks; split it into several pages");
    }
    chunks.add(new Chunk(chunks.size(), headingPath, content));
  }

  /**
   * The heading path, deepest heading last.
   *
   * <p>When it will not fit the column, the leading segments go rather than the trailing ones: the deepest heading
   * is the one that identifies the section, and a citation truncated at the front still points somewhere real.
   */
  private String path(String root, Deque<String> headings) {
    List<String> segments = new ArrayList<>();
    segments.add(root);
    // Most pages open with their own title as an h1, which would otherwise cite as "Intake > Intake". A segment that
    // repeats the one before it adds nothing to a citation, so it is collapsed — case- and whitespace-insensitively,
    // because a title and a heading that differ only in capitalisation are still the same place in the document.
    headings.stream()
        .filter(segment -> !segment.isEmpty())
        .forEach(segment -> {
          if (!segment.strip().equalsIgnoreCase(segments.get(segments.size() - 1).strip())) segments.add(segment);
        });
    String path = String.join(SEPARATOR, segments);
    if (path.length() <= MAX_HEADING_PATH) return path;

    List<String> kept = new ArrayList<>();
    int length = 1; // the leading ellipsis
    for (int index = segments.size() - 1; index >= 0; index--) {
      int cost = segments.get(index).length() + SEPARATOR.length();
      if (length + cost > MAX_HEADING_PATH) break;
      length += cost;
      kept.add(0, segments.get(index));
    }
    if (kept.isEmpty()) return segments.get(segments.size() - 1).substring(0, MAX_HEADING_PATH);
    return "…" + SEPARATOR + String.join(SEPARATOR, kept);
  }
}
