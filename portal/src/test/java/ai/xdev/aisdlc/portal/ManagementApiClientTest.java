package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class ManagementApiClientTest {
  @Test
  void mapsUnauthorizedResponsesToSessionRecoveryWithoutLeakingStatusDetails() {
    var unauthorized = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    String message = ManagementApiClient.errorMessage(unauthorized);
    assertEquals(ManagementApiClient.AUTHENTICATION_REQUIRED, message);
    assertTrue(ManagementApiClient.requiresSessionRecovery(message));
  }

  @Test
  void keepsForbiddenResponsesUserSafeAndDistinctFromExpiredSessions() {
    var forbidden = HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    String message = ManagementApiClient.errorMessage(forbidden);
    assertEquals("Control plane denied this request. Check project access and try again.", message);
    assertFalse(ManagementApiClient.requiresSessionRecovery(message));
  }
}
