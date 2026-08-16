package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.service.EnterpriseTenantService;
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
