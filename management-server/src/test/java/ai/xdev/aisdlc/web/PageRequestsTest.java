package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PageRequestsTest {
  @Test
  void createsAllowListedAscendingPageRequest() {
    var request = PageRequests.of(2, 25, "createdAt,asc", "createdAt", "status");
    assertEquals(2, request.getPageNumber());
    assertEquals(25, request.getPageSize());
    assertEquals(org.springframework.data.domain.Sort.Direction.ASC, request.getSort().getOrderFor("createdAt").getDirection());
  }

  @Test
  void rejectsUnboundedOrUnsupportedPaginationRequests() {
    assertThrows(IllegalArgumentException.class, () -> PageRequests.of(-1, 25, "createdAt,desc", "createdAt"));
    assertThrows(IllegalArgumentException.class, () -> PageRequests.of(0, 101, "createdAt,desc", "createdAt"));
    assertThrows(IllegalArgumentException.class, () -> PageRequests.of(0, 25, "actorSubject,asc", "createdAt"));
  }

  @Test
  void detectsIntegerOverflowWhenCalculatingOffsets() {
    assertThrows(ArithmeticException.class, () -> PageRequests.offset(Integer.MAX_VALUE, 100));
  }
}
