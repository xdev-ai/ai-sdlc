package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.domain.Project;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class GovernanceCatalogServiceTest {
  @Test
  void prohibitsNonFinalHumanDecision() {
    ProjectAccessService access = mock(ProjectAccessService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    GovernanceCatalogService service = new GovernanceCatalogService(jdbc, access, mock(AuditService.class));
    UUID projectId = UUID.randomUUID();
    when(access.requireMembership(eq(projectId), anyString(), any(MembershipRole[].class))).thenReturn(new Project(UUID.randomUUID(), "demo", "Demo", ""));
    assertThrows(IllegalArgumentException.class, () -> service.decideReview(projectId, UUID.randomUUID(), "reviewer", ReviewStatus.PENDING, ""));
    verifyNoInteractions(jdbc);
  }
}

