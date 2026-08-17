package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.xdev.aisdlc.service.MarkdownChunker.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The chunker decides what an AI is shown and what a generated answer can cite, so its rules are checked directly
 * rather than through the database that stores the result.
 *
 * <p>The fence cases are here because of a real defect earlier in this repository: a line-oriented pass over
 * Markdown treated {@code #} comments inside code fences as headings and corrupted the documents it was merging.
 * A chunker with the same blind spot would cut code samples in half and file the remainder under a section that
 * does not exist.
 */
class MarkdownChunkerTest {
  private final MarkdownChunker chunker = new MarkdownChunker();

  @Test void headingPathNamesTheSectionSoAnAnswerCanCiteItRatherThanTheWholePage() {
    List<Chunk> chunks = chunker.chunk("Reception", """
        # Intake
        Register the patient.

        ## Insurance check
        Verify the insurance card.
        """);

    assertEquals(2, chunks.size());
    assertEquals("Reception > Intake", chunks.get(0).headingPath());
    assertEquals("Register the patient.", chunks.get(0).content());
    assertEquals("Reception > Intake > Insurance check", chunks.get(1).headingPath());
    assertEquals("Verify the insurance card.", chunks.get(1).content());
    assertEquals(List.of(0, 1), chunks.stream().map(Chunk::ordinal).toList());
  }

  @Test void aHashInsideACodeFenceIsAShellCommentAndMustNotStartANewSection() {
    List<Chunk> chunks = chunker.chunk("Runbook", """
        ## Deploy
        Run the script:

        ```bash
        # rotate the key first
        ./deploy.sh

        # then restart
        systemctl restart app
        ```

        Confirm the version afterwards.
        """);

    assertTrue(chunks.stream().allMatch(chunk -> chunk.headingPath().equals("Runbook > Deploy")),
        "a comment inside a fence invented a section: " + chunks.stream().map(Chunk::headingPath).toList());
    String all = String.join("\n", chunks.stream().map(Chunk::content).toList());
    assertTrue(all.contains("# rotate the key first"), "the comment must survive as content");
    assertEquals(2, all.split("```", -1).length - 1, "the fence must stay balanced: " + all);
  }

  @Test void anOversizedFenceIsEmittedWholeBecauseHalfACodeSampleIsWorseThanAnOversizedChunk() {
    // The fence is ~610 characters against a 400-character limit: the packer must not divide it anyway.
    String line = "x".repeat(300);
    List<Chunk> chunks = new MarkdownChunker(400).chunk("Sample", """
        ## Code
        ```
        %s

        %s
        ```
        """.formatted(line, line));

    assertEquals(1, chunks.size(), "the fence was divided: " + chunks.size() + " chunks");
    Chunk only = chunks.get(0);
    assertEquals(2, only.content().lines().filter(candidate -> candidate.startsWith("```")).count(),
        "a chunk carries an unbalanced fence:\n" + only.content());
    assertTrue(only.content().length() > 400, "this is the documented exception to the size limit");
    assertTrue(only.content().contains(line + "\n\n" + line), "the blank line inside the fence must survive verbatim");
  }

  @Test void aFencedBlockBeyondTheHardLimitIsRefusedRatherThanBlowingEveryPromptBudget() {
    String huge = "```\n" + "z".repeat(MarkdownChunker.MAX_FENCE_CHARS + 1) + "\n```\n";

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> chunker.chunk("Dump", huge));
    assertTrue(refused.getMessage().contains(String.valueOf(MarkdownChunker.MAX_FENCE_CHARS)), refused.getMessage());
  }

  @Test void anOversizedSectionIsDividedBetweenParagraphsRatherThanMidSentence() {
    String paragraph = "Bệnh nhân được tiếp nhận tại quầy. ".repeat(12); // ~420 characters
    List<Chunk> chunks = new MarkdownChunker(500).chunk("Reception", """
        ## Intake
        %s

        %s

        %s
        """.formatted(paragraph, paragraph, paragraph));

    assertTrue(chunks.size() >= 3, "a 1260-character section must not fit in one 500-character chunk");
    for (Chunk chunk : chunks) {
      assertTrue(chunk.content().length() <= 500, "chunk exceeds the limit: " + chunk.content().length());
      assertTrue(chunk.content().strip().endsWith("."), "a chunk was cut mid-sentence: ..." + tail(chunk.content()));
      assertEquals("Reception > Intake", chunk.headingPath());
    }
  }

  @Test void aSingleLineLongerThanTheLimitIsCutBecauseNothingElseIsLeftToSplitOn() {
    List<Chunk> chunks = new MarkdownChunker(200).chunk("Data", "y".repeat(650));

    assertEquals(4, chunks.size());
    assertEquals(650, chunks.stream().mapToInt(chunk -> chunk.content().length()).sum(), "no content may be dropped");
    assertTrue(chunks.stream().allMatch(chunk -> chunk.content().length() <= 200));
  }

  @Test void aHeadingWithNoBodyBeneathItIsNotAChunkBecauseThereIsNothingToRetrieve() {
    List<Chunk> chunks = chunker.chunk("Guide", """
        # Part one

        ## Section
        Actual content.

        # Part two
        """);

    assertEquals(1, chunks.size(), "empty sections produced chunks: " + chunks.stream().map(Chunk::headingPath).toList());
    assertEquals("Guide > Part one > Section", chunks.get(0).headingPath());
  }

  @Test void aBodyOpeningWithItsOwnTitleDoesNotCiteTheSameNameTwice() {
    List<Chunk> chunks = chunker.chunk("Tiếp nhận người bệnh", """
        # Tiếp nhận người bệnh
        Nhân viên kiểm tra giấy tờ.

        ## Kiểm tra bảo hiểm
        Xác minh thẻ bảo hiểm.
        """);

    assertEquals("Tiếp nhận người bệnh", chunks.get(0).headingPath(), "the repeated h1 was not collapsed");
    assertEquals("Tiếp nhận người bệnh > Kiểm tra bảo hiểm", chunks.get(1).headingPath());
  }

  @Test void aRepeatedHeadingIsCollapsedRegardlessOfCapitalisation() {
    List<Chunk> chunks = chunker.chunk("Runbook", """
        # RUNBOOK
        Steps below.
        """);

    assertEquals("Runbook", chunks.get(0).headingPath());
  }

  @Test void aHeadingThatMerelyResemblesItsParentIsKept() {
    List<Chunk> chunks = chunker.chunk("Intake", """
        # Intake process
        Details.
        """);

    assertEquals("Intake > Intake process", chunks.get(0).headingPath(), "collapsing must be exact, not fuzzy");
  }

  @Test void textBeforeAnyHeadingIsFiledUnderThePageTitle() {
    List<Chunk> chunks = chunker.chunk("Overview", """
        This note has a preamble.

        ## Later
        And a section.
        """);

    assertEquals("Overview", chunks.get(0).headingPath());
    assertEquals("Overview > Later", chunks.get(1).headingPath());
  }

  @Test void aBodyWithNoHeadingsAtAllStillCarriesAPathBecauseTheColumnIsNotNullable() {
    List<Chunk> chunks = chunker.chunk("Short note", "Just one paragraph.");

    assertEquals(1, chunks.size());
    assertEquals("Short note", chunks.get(0).headingPath());
  }

  @Test void aSkippedHeadingLevelKeepsTheDeepestHeadingLastSoTheCitationStaysReadable() {
    List<Chunk> chunks = chunker.chunk("Doc", """
        ## Two
        ##### Five
        Content under a jumped level.
        """);

    assertEquals("Doc > Two > Five", chunks.get(0).headingPath(), "padding leaked into the path");
  }

  @Test void anOverlongHeadingPathKeepsTheDeepestSegmentsBecauseThoseIdentifyTheSection() {
    // Each heading must be distinct: identical ones collapse, which is a different rule under test elsewhere.
    StringBuilder body = new StringBuilder();
    for (int level = 1; level <= 6; level++) {
      body.append("#".repeat(level)).append(' ').append(String.valueOf((char) ('A' + level)).repeat(120)).append('\n');
    }
    body.append("\nContent.\n");
    String deepest = "G".repeat(120);

    Chunk chunk = chunker.chunk("Root", body.toString()).get(0);

    assertTrue(chunk.headingPath().length() <= MarkdownChunker.MAX_HEADING_PATH,
        "heading_path is varchar(600) and this is " + chunk.headingPath().length());
    assertTrue(chunk.headingPath().startsWith("…"), "truncation must be visible: " + chunk.headingPath());
    assertTrue(chunk.headingPath().endsWith(deepest), "the deepest heading must survive: " + chunk.headingPath());
    assertFalse(chunk.headingPath().startsWith("Root"), "the leading segments are the ones that go");
  }

  @Test void anEmptyOrBlankBodyProducesNothingRatherThanAnEmptyChunk() {
    assertTrue(chunker.chunk("Title", null).isEmpty());
    assertTrue(chunker.chunk("Title", "").isEmpty());
    assertTrue(chunker.chunk("Title", "   \n\n  \n").isEmpty());
  }

  @Test void aRunawayBodyIsRefusedRatherThanWritingThousandsOfRows() {
    StringBuilder body = new StringBuilder();
    for (int section = 0; section <= MarkdownChunker.MAX_CHUNKS; section++) {
      body.append("## S").append(section).append("\ncontent\n\n");
    }

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> chunker.chunk("Big", body.toString()));
    assertTrue(refused.getMessage().contains(String.valueOf(MarkdownChunker.MAX_CHUNKS)), refused.getMessage());
  }

  @Test void chunkingIsDeterministicSoAContentDigestIsAStableIdentity() {
    String body = """
        # A
        one

        ## B
        two
        """;

    assertEquals(chunker.chunk("Page", body), chunker.chunk("Page", body));
  }

  @Test void anUnterminatedFenceKeepsItsTextInsteadOfSilentlyDroppingTheTail() {
    List<Chunk> chunks = chunker.chunk("Broken", """
        ## Code
        ```sql
        select 1;
        """);

    assertEquals(1, chunks.size());
    assertTrue(chunks.get(0).content().contains("select 1;"), "content after an unclosed fence was lost");
  }

  @Test void aChunkSizeOutsideTheSupportedRangeIsRefusedAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new MarkdownChunker(199));
    assertThrows(IllegalArgumentException.class, () -> new MarkdownChunker(20_001));
  }

  private static String tail(String content) {
    return content.substring(Math.max(0, content.length() - 40));
  }
}
