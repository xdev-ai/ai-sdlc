package ai.xdev.aisdlc.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.AuditEvent;
import ai.xdev.aisdlc.domain.Organization;
import ai.xdev.aisdlc.repo.Repositories.AuditEventRepository;
import ai.xdev.aisdlc.repo.Repositories.OrganizationRepository;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditServiceTest {
  @Test
  void buildsASequentialHashChain() throws Exception {
    UUID orgId = UUID.randomUUID();
    OrganizationRepository organizations = mock(OrganizationRepository.class);
    AuditEventRepository events = mock(AuditEventRepository.class);
    when(organizations.lockById(orgId)).thenReturn(Optional.of(new Organization("xdev", "xDev")));
    when(events.save(any(AuditEvent.class))).thenAnswer(call -> call.getArgument(0));
    AuditService service = new AuditService(organizations, events);

    AuditEvent first = service.append(orgId, null, "alice", "policy.created", "policy", "1", "{}");
    when(events.findTopByOrganizationIdOrderBySequenceDesc(orgId)).thenReturn(Optional.of(first));
    AuditEvent second = service.append(orgId, null, "alice", "policy.updated", "policy", "1", "{}");

    assertEquals(1, first.getSequence());
    assertEquals(2, second.getSequence());
    assertNotEquals(first.getEventHash(), second.getEventHash());
    Field previousHash = AuditEvent.class.getDeclaredField("previousHash"); previousHash.setAccessible(true);
    assertEquals(first.getEventHash(), previousHash.get(second));
  }
}

