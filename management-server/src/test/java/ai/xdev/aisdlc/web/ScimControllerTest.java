package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.service.EnterpriseTenantService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ScimControllerTest {
  @Test void rejectsInvalidProvisioningTokenBeforeReadingOrWritingUsers() {
    EnterpriseTenantService service = mock(EnterpriseTenantService.class);
    ScimController controller = new ScimController(service);
    UUID tenantId = UUID.randomUUID();
    when(service.authorizeScim(tenantId, "invalid")).thenReturn(false);

    ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.list(tenantId, "Bearer invalid", 1, 10));

    assertEquals(401, error.getStatusCode().value());
    verify(service).authorizeScim(tenantId, "invalid");
    verifyNoMoreInteractions(service);
  }

  @Test void hostileStartIndexAndCountCannotUnderflowTheRequestedPage() {
    EnterpriseTenantService service = mock(EnterpriseTenantService.class);
    ScimController controller = new ScimController(service);
    UUID tenantId = UUID.randomUUID();
    when(service.authorizeScim(tenantId, "valid")).thenReturn(true);
    when(service.scimUsers(eq(tenantId), anyInt(), anyInt())).thenReturn(List.of());

    // Integer.MIN_VALUE - 1 wraps to Integer.MAX_VALUE, so the old Math.max(0, startIndex - 1) clamp did not clamp:
    // it asked for page 21474836 and returned an empty result for what SCIM defines as the first page.
    controller.list(tenantId, "Bearer valid", Integer.MIN_VALUE, 100);
    verify(service).scimUsers(tenantId, 0, 100);

    // The divisor must use the same clamp the service applies, or the page number is computed against a page size
    // the service will never use. count = 1_000_000 becomes 100, so startIndex 201 is page 2, not page 0.
    controller.list(tenantId, "Bearer valid", 201, 1_000_000);
    verify(service).scimUsers(tenantId, 2, 100);
  }

  @Test void createsTenantScopedScimUserWithStableSubjectWhenExternalIdIsAbsent() {
    EnterpriseTenantService service = mock(EnterpriseTenantService.class);
    ScimController controller = new ScimController(service);
    UUID tenantId = UUID.randomUUID(); UUID userId = UUID.randomUUID();
    when(service.authorizeScim(tenantId, "valid")).thenReturn(true);
    when(service.provisionScimUser(eq(tenantId), isNull(), eq("scim:alice@example.com"), eq("Alice@example.com"), eq("Alice"), eq(true), anyMap()))
        .thenReturn(new EnterpriseTenantService.ScimUserView(userId, null, "scim:alice@example.com", "Alice@example.com", "Alice", true, Map.of()));

    Map<String, Object> response = controller.create(tenantId, "Bearer valid", Map.of("userName", "Alice@example.com", "displayName", "Alice", "active", true));

    assertEquals(userId.toString(), response.get("id"));
    assertEquals("Alice@example.com", response.get("userName"));
    verify(service).provisionScimUser(eq(tenantId), isNull(), eq("scim:alice@example.com"), eq("Alice@example.com"), eq("Alice"), eq(true), anyMap());
  }
}
