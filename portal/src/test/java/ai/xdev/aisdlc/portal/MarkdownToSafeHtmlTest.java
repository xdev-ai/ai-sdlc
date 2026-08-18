package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * This class turns user-authored text into markup, so the tests that matter most are the ones trying to break out of
 * it. If any of the injection cases below regress, every reader of a documentation space is exposed.
 */
class MarkdownToSafeHtmlTest {
  @Test void headingsBecomeSubordinateHeadingsSoAPageCannotOutrankItsOwnTitle() {
    String html = MarkdownToSafeHtml.render("# Top\n\n## Second\n\n#### Fourth\n");

    assertTrue(html.contains("<h3>Top</h3>"), html);
    assertTrue(html.contains("<h4>Second</h4>"), html);
    assertTrue(html.contains("<h6>Fourth</h6>"), html);
    assertFalse(html.contains("<h1"), "a page body must not emit an h1");
    assertFalse(html.contains("<h2"), "nor an h2, which is the panel title's level");
  }

  @Test void paragraphsListsAndInlineMarkupRender() {
    String html = MarkdownToSafeHtml.render("""
        Nhân viên tiếp nhận kiểm tra giấy tờ.

        - Nhập thông tin
        - Gán mã người bệnh

        1. Bước một
        2. Bước hai

        Chú ý **quan trọng** và `mã lệnh`.
        """);

    assertTrue(html.contains("<p>Nhân viên tiếp nhận kiểm tra giấy tờ.</p>"), html);
    assertTrue(html.contains("<ul><li>Nhập thông tin</li><li>Gán mã người bệnh</li></ul>"), html);
    assertTrue(html.contains("<ol><li>Bước một</li><li>Bước hai</li></ol>"), html);
    assertTrue(html.contains("<strong>quan trọng</strong>"), html);
    assertTrue(html.contains("<code>mã lệnh</code>"), html);
  }

  @Test void fencedCodeIsPreservedAsCodeAndItsContentIsEscaped() {
    String html = MarkdownToSafeHtml.render("```\n<script>alert(1)</script>\n# not a heading\n```\n");

    assertTrue(html.contains("<pre class=\"doc-code\"><code>"), html);
    assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html);
    assertFalse(html.contains("<script>"), "a script inside a fence must stay text");
    assertFalse(html.contains("<h3># not a heading</h3>"), "a hash inside a fence is not a heading");
  }

  // --- the cases this class exists to survive ---------------------------------------------------------------------

  @Test void noAuthorInputCanProduceAnElement() {
    List<String> attacks = List.of(
        "<script>window.stolen=document.cookie</script>",
        "<img src=x onerror=alert(1)>",
        "<iframe src=\"javascript:alert(1)\"></iframe>",
        "<svg/onload=alert(1)>",
        "<body onload=alert(1)>",
        "<a href=\"javascript:alert(1)\">click</a>",
        "<style>body{display:none}</style>",
        "<!--<script>alert(1)</script>-->",
        "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>",
        "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\">");

    for (String attack : attacks) {
      String html = MarkdownToSafeHtml.render(attack);
      // The check is that no element or attribute exists — not that a dangerous-looking string is absent. The
      // characters "onerror=alert(1)" legitimately survive inside escaped text, where they cannot execute, and an
      // assertion on that substring fails while the code is correct. Do not weaken the class to satisfy one.
      assertOnlyAllowedTags(html, attack);
      assertTrue(html.contains("&lt;"), "the attack should appear as escaped text: " + attack + " -> " + html);
    }
  }

  /** The only tags permitted in the output are the ones this class writes itself. */
  private static void assertOnlyAllowedTags(String html, String context) {
    List<String> allowed = List.of("p", "/p", "h3", "/h3", "h4", "/h4", "h5", "/h5", "h6", "/h6",
        "ul", "/ul", "li", "/li", "ol", "/ol", "strong", "/strong", "code", "/code",
        "pre class=\"doc-code\"", "/pre");
    java.util.regex.Matcher tags = java.util.regex.Pattern.compile("<([^>]*)>").matcher(html);
    while (tags.find()) {
      assertTrue(allowed.contains(tags.group(1)),
          "unexpected tag <" + tags.group(1) + "> from " + context + " -> " + html);
    }
  }

  /** Only the tags this class writes may appear. Anything else means author text reached the markup. */
  @Test void theOnlyTagsInTheOutputAreTheOnesThisClassWrites() {
    String html = MarkdownToSafeHtml.render("""
        # Heading <b>bold</b>

        Text with <div>markup</div> and a "quote" and 'apostrophe' and & ampersand.

        - item <span>x</span>

        ```
        <table><tr><td>
        ```
        """);

    assertOnlyAllowedTags(html, "a body mixing markdown with raw HTML");
  }

  @Test void anAmpersandIsEscapedOnceAndNotDoubleEscaped() {
    String html = MarkdownToSafeHtml.render("Tiếp nhận & xác minh\n");

    assertTrue(html.contains("Tiếp nhận &amp; xác minh"), html);
    assertFalse(html.contains("&amp;amp;"), "double escaping would show the entity to the reader");
  }

  /** A dollar sign in author text must not be read as a regex group reference. That exact mistake corrupted a source
   *  file earlier in this project, so it is pinned here. */
  @Test void aDollarSignInBoldTextSurvivesUnchanged() {
    String html = MarkdownToSafeHtml.render("Giá **$1,160** mỗi tháng\n");

    assertTrue(html.contains("<strong>$1,160</strong>"), html);
  }

  @Test void anEmptyOrBlankBodyRendersNothing() {
    assertEquals("", MarkdownToSafeHtml.render(null));
    assertEquals("", MarkdownToSafeHtml.render(""));
    assertEquals("", MarkdownToSafeHtml.render("   \n\n  "));
  }

  @Test void aBodyBeyondTheInputCeilingIsTruncatedRatherThanRendered() {
    String huge = "a".repeat(MarkdownToSafeHtml.MAX_INPUT_CHARS + 5_000);

    String html = MarkdownToSafeHtml.render(huge);

    assertTrue(html.length() < MarkdownToSafeHtml.MAX_INPUT_CHARS + 100,
        "output grew past the ceiling: " + html.length());
  }

  @Test void anUnterminatedFenceKeepsItsText() {
    String html = MarkdownToSafeHtml.render("```sql\nselect 1;\n");

    assertTrue(html.contains("select 1;"), "content after an unclosed fence was lost: " + html);
  }
}
