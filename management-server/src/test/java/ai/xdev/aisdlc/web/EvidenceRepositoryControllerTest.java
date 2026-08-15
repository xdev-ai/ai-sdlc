package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.domain.EvidenceAsset;
import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.service.EvidenceRepositoryService;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;

class EvidenceRepositoryControllerTest {
  @Test
  void forwardsMultipartAndAuthenticatedSubjectToGovernedService() throws Exception {
    EvidenceRepositoryService service = mock(EvidenceRepositoryService.class);
    EvidenceRepositoryController controller = new EvidenceRepositoryController(service);
    UUID projectId = UUID.randomUUID(); EvidenceAsset asset = asset(projectId);
    when(service.upload(eq(projectId), eq("developer-1"), eq("upload-controller-key"), eq(EvidenceAssetType.SPECIFICATION), eq(EvidenceAccessLevel.REVIEWERS), isNull(), eq("spec.md"), eq("text/markdown"), argThat(bytes -> java.util.Arrays.equals(bytes, "# Spec".getBytes())), isNull())).thenReturn(asset);
    MockMultipartFile file = new MockMultipartFile("file", "spec.md", "text/markdown", "# Spec".getBytes());

    var response = controller.upload(projectId, file, EvidenceAssetType.SPECIFICATION, EvidenceAccessLevel.REVIEWERS, null, null, "upload-controller-key", jwt("developer-1"));

    assertEquals(asset.getId(), response.id());
    assertEquals(EvidenceAccessLevel.REVIEWERS, response.accessLevel());
    verify(service).upload(eq(projectId), eq("developer-1"), eq("upload-controller-key"), eq(EvidenceAssetType.SPECIFICATION), eq(EvidenceAccessLevel.REVIEWERS), isNull(), eq("spec.md"), eq("text/markdown"), argThat(bytes -> java.util.Arrays.equals(bytes, "# Spec".getBytes())), isNull());
  }

  private EvidenceAsset asset(UUID projectId) throws Exception {
    EvidenceAsset asset = new EvidenceAsset(projectId, null, EvidenceAssetType.SPECIFICATION, "spec.md", "text/markdown", 6, "bucket", "key", "a".repeat(64), "upload-controller-key", "developer-1", EvidenceAccessLevel.REVIEWERS);
    Field field = EvidenceAsset.class.getDeclaredField("id"); field.setAccessible(true); field.set(asset, UUID.randomUUID());
    return asset;
  }
  private Jwt jwt(String subject) { return Jwt.withTokenValue("test").header("alg", "none").subject(subject).build(); }
}
