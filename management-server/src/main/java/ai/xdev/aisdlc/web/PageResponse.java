package ai.xdev.aisdlc.web;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable, bounded response envelope used by all collection resources. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {
  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
  }

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalItems) {
    int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalItems / size);
    return new PageResponse<>(items, page, size, totalItems, totalPages, page + 1 < totalPages);
  }
}
