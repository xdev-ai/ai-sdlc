package ai.xdev.aisdlc.web;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable, bounded response envelope used by all collection resources. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {
  public static <T> PageResponse<T> from(Page<T> page) {
    // Not page.hasNext(). Spring Data computes it as getNumber() + 1 < getTotalPages() in int arithmetic, which wraps
    // at page = Integer.MAX_VALUE exactly as of() did before it was widened. Fixing of() alone left the overflow live
    // on every endpoint that builds its envelope from a Page — which is most of them, including the audit ledger.
    // Found by calling the running API, not by the unit test that covered of().
    return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
        page.getTotalPages(), (long) page.getNumber() + 1 < page.getTotalPages());
  }

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
    // All arithmetic here is in long. With int arithmetic, page = Integer.MAX_VALUE (reachable through
    // /governance/... ?page=2147483647&size=1, where PageRequests.offset permits it because 2147483647 * 1 does not
    // overflow) made page + 1 wrap to Integer.MIN_VALUE, so hasNext reported true on an empty final page.
    long totalPages = size <= 0 ? 0 : Math.ceilDiv(totalItems, (long) size);
    return new PageResponse<>(items, page, size, totalItems,
        (int) Math.min(totalPages, Integer.MAX_VALUE), (long) page + 1 < totalPages);
  }
}
