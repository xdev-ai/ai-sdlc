package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.service.RuntimeAiBrokerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RuntimeAiBrokerControllerTest {
  @Test void bindsProviderAuthorizationToAuthenticatedWorkloadSubject() throws Exception {
    var broker=mock(RuntimeAiBrokerService.class); var controller=new RuntimeAiBrokerController(broker); UUID project=UUID.randomUUID(), session=UUID.randomUUID(); String fingerprint="b".repeat(64); var context=new ObjectMapper().readTree("{\"providerAllowed\":true}"); var result=new RuntimeAiBrokerService.AuthorizationView("ALLOW","POLICY_PASS",UUID.randomUUID(),UUID.randomUUID());
    when(broker.preflight(project,"workload-1",session,"provider-a","model-a",fingerprint,context,false)).thenReturn(result);
    assertEquals(result,controller.authorizeProvider(project,new RuntimeAiBrokerController.ProviderRequest(session,"provider-a","model-a",fingerprint,context,false),jwt("workload-1")));
    verify(broker).preflight(project,"workload-1",session,"provider-a","model-a",fingerprint,context,false);
  }
  private Jwt jwt(String subject){return Jwt.withTokenValue("token").header("alg","none").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();}
}
