package ai.xdev.aisdlc.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Applies a bounded, allow-listed sort contract to query parameters. */
public final class PageRequests {
  private static final int MAX_SIZE = 100;

  private PageRequests() {}

  public static Pageable of(int page, int size, String sort, String... allowedProperties) {
    if (page < 0) throw new IllegalArgumentException("page must be zero or greater");
    if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
    String requested = sort == null || sort.isBlank() ? allowedProperties[0] : sort;
    String[] tokens = requested.split(",", 2);
    String property = tokens[0].trim();
    boolean allowed = java.util.Arrays.stream(allowedProperties).anyMatch(property::equals);
    if (!allowed) throw new IllegalArgumentException("Unsupported sort property: " + property);
    Sort.Direction direction = tokens.length == 2 && "asc".equalsIgnoreCase(tokens[1].trim()) ? Sort.Direction.ASC : Sort.Direction.DESC;
    return PageRequest.of(page, size, Sort.by(direction, property));
  }

  public static int offset(int page, int size) {
    if (page < 0) throw new IllegalArgumentException("page must be zero or greater");
    if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
    return Math.multiplyExact(page, size);
  }
}
