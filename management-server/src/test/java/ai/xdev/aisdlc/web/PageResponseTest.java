package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The envelope is computed from caller-supplied paging parameters, so its arithmetic must survive hostile ones. */
class PageResponseTest {
  @Test void extremePageNumberDoesNotWrapIntoAFalseHasNext() {
    // PageRequests.offset permits page = Integer.MAX_VALUE when size is 1, because the multiply does not overflow.
    // With int arithmetic, page + 1 then wrapped to Integer.MIN_VALUE and hasNext reported true past the last page.
    PageResponse<String> response = PageResponse.of(List.of(), Integer.MAX_VALUE, 1, 3);

    assertFalse(response.hasNext());
    assertEquals(3, response.totalPages());
  }

  @Test void totalPagesSaturatesRatherThanWrappingWhenItExceedsAnInt() {
    PageResponse<String> response = PageResponse.of(List.of(), 0, 1, 5_000_000_000L);

    assertEquals(Integer.MAX_VALUE, response.totalPages());
    assertTrue(response.hasNext());
  }

  @Test void ordinaryPagingIsUnchanged() {
    assertEquals(4, PageResponse.of(List.of("a"), 0, 25, 100).totalPages());
    assertEquals(5, PageResponse.of(List.of("a"), 0, 25, 101).totalPages());
    assertTrue(PageResponse.of(List.of("a"), 0, 25, 100).hasNext());
    assertFalse(PageResponse.of(List.of("a"), 3, 25, 100).hasNext());
    assertEquals(0, PageResponse.of(List.of(), 0, 25, 0).totalPages());
  }

  @Test void nonPositiveSizeYieldsNoPagesInsteadOfDividingByZeroOrGoingNegative() {
    assertEquals(0, PageResponse.of(List.of(), 0, 0, 10).totalPages());
    assertEquals(0, PageResponse.of(List.of(), 0, -25, 10).totalPages());
    assertFalse(PageResponse.of(List.of(), 0, -25, 10).hasNext());
  }
}
