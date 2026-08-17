package ai.xdev.aisdlc.web;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable, bounded response envelope used by all collection resources. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {
  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
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
