package ai.xdev.aisdlc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import ai.xdev.aisdlc.service.BudgetEnforcementService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class BudgetEnforcementControllerTest {
  @Test void configuresBudgetAndForwardsAuthenticatedActor() {
    var service=mock(BudgetEnforcementService.class); var controller=new BudgetEnforcementController(service); UUID project=UUID.randomUUID(); var view=new BudgetEnforcementService.BudgetPolicyView(UUID.randomUUID(),"USD",12000,80,"HOLD",true);
    when(service.configure(project,"owner-1","USD",12000,80,"HOLD")).thenReturn(view);
    assertEquals(view,controller.configure(project,new BudgetEnforcementController.BudgetInput("USD",12000,80,"HOLD"),jwt("owner-1")));
    verify(service).configure(project,"owner-1","USD",12000,80,"HOLD");
  }
  @Test void requestsExpiryBoundExceptionWithRationaleDigest() {
    var service=mock(BudgetEnforcementService.class); var controller=new BudgetEnforcementController(service); UUID project=UUID.randomUUID(), approval=UUID.randomUUID(); String digest="a".repeat(64); LocalDate expiry=LocalDate.now().plusDays(2);
    controller.requestException(project,new BudgetEnforcementController.ExceptionInput(approval,expiry,digest),jwt("developer-1"));
    verify(service).requestException(project,"developer-1",approval,expiry,digest);
  }
  private Jwt jwt(String subject){return Jwt.withTokenValue("token").header("alg","none").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120)).build();}
}
