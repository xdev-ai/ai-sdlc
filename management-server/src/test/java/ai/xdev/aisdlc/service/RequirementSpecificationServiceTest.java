package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.xdev.aisdlc.domain.Project;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RequirementSpecificationServiceTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ProjectAccessService access = mock(ProjectAccessService.class);
  private final AuditService audit = mock(AuditService.class);
  private final RequirementSpecificationService service = new RequirementSpecificationService(jdbc, access, audit);

  private final UUID projectId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID kitId = UUID.randomUUID();
  private final String actor = "tester";

  @BeforeEach
  void projectExists() {
    Project project = mock(Project.class);
    when(project.getOrganizationId()).thenReturn(organizationId);
    when(access.requireMembership(eq(projectId), eq(actor), any(), any())).thenReturn(project);
  }

  private void bothEndsBelong() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(nodeId), eq(projectId))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(kitId), eq(organizationId))).thenReturn(1);
  }

  @Test
  @DisplayName("a first link needs no supersede reason, because nothing is being replaced")
  void firstLinkNeedsNoReason() {
    bothEndsBelong();
    when(jdbc.queryForObject(anyString(), eq(String.class), eq(kitId))).thenReturn("ACTIVE");
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(nodeId))).thenReturn(List.of());

    UUID id = service.link(projectId, actor, nodeId, kitId, "SPEC-042_v1.0", null);

    assertNotNull(id);
    // Nothing was open, so no row may be closed. A blanket "close then insert" would corrupt a neighbouring link.
    verify(jdbc, never()).update(anyString(), eq(actor), anyString(), any(UUID.class));
    verify(audit).append(eq(organizationId), eq(projectId), eq(actor), eq("requirement.specification.linked"),
        eq("requirement_specification"), anyString(), anyString());
  }

  @Test
  @DisplayName("replacing an existing link without saying why is refused")
  void supersedingRequiresAReason() {
    bothEndsBelong();
    when(jdbc.queryForObject(anyString(), eq(String.class), eq(kitId))).thenReturn("ACTIVE");
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(nodeId))).thenReturn(List.of(UUID.randomUUID()));

    // Blank is as bad as absent: the reason is the record, and " " records nothing.
    for (String reason : new String[]{null, "", "   "}) {
      IllegalArgumentException refused =
          assertThrows(IllegalArgumentException.class, () -> service.link(projectId, actor, nodeId, kitId, "SPEC-042_v2.0", reason));
      assertEquals("Superseding an existing specification requires a reason", refused.getMessage());
    }
    // The refusal must happen before anything is written, or a rejected request still closed the previous link.
    verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a deprecated document version cannot become the current specification")
  void deprecatedDocumentIsRefused() {
    bothEndsBelong();
    when(jdbc.queryForObject(anyString(), eq(String.class), eq(kitId))).thenReturn("DEPRECATED");

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> service.link(projectId, actor, nodeId, kitId, "SPEC-001_v1.0", null));
    assertEquals("A DEPRECATED document version cannot be assigned as the current specification", refused.getMessage());
  }

  @Test
  @DisplayName("a requirement from another project is refused before any write")
  void foreignRequirementIsRefused() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(nodeId), eq(projectId))).thenReturn(0);

    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> service.link(projectId, actor, nodeId, kitId, "SPEC-001_v1.0", null));
    assertEquals("The requirement does not belong to this project", refused.getMessage());
    verify(audit, never()).append(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test
  @DisplayName("a document version from another organization is refused")
  void foreignDocumentIsRefused() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(nodeId), eq(projectId))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(kitId), eq(organizationId))).thenReturn(0);

    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> service.link(projectId, actor, nodeId, kitId, "SPEC-001_v1.0", null));
    assertEquals("The specification document does not belong to this organization", refused.getMessage());
  }

  @Test
  @DisplayName("closing a link that does not exist is refused, rather than silently doing nothing")
  void closingWithoutACurrentLinkIsRefused() {
    when(jdbc.queryForList(anyString(), eq(UUID.class), eq(nodeId))).thenReturn(List.of());

    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> service.unlink(projectId, actor, nodeId, "document withdrawn"));
    assertEquals("This requirement has no current specification", refused.getMessage());
  }

  @Test
  @DisplayName("closing a link requires a reason too")
  void closingRequiresAReason() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> service.unlink(projectId, actor, nodeId, "  "));
    assertEquals("Closing a specification link requires a reason", refused.getMessage());
    // The reason check comes first, so a blank reason never reaches a lookup, let alone a write.
    verify(jdbc, never()).queryForList(anyString(), eq(UUID.class), any(UUID.class));
  }
}
