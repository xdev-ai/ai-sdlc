package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PortalSecurityConfigTest {
  @Test
  void keepsBrowserSecurityPolicyAndOnlyMinimalPublicRoutes() throws Exception {
    String source = Files.readString(Path.of("src/main/java/ai/xdev/aisdlc/portal/PortalSecurityConfig.java"));

    assertTrue(source.contains("contentSecurityPolicy"));
    assertTrue(source.contains("frame-ancestors 'self'"));
    assertTrue(source.contains("httpStrictTransportSecurity"));
    assertTrue(source.contains("/actuator/health"));
    assertTrue(source.contains(".requestMatchers(\"/app/**\").authenticated()"));
    assertTrue(source.contains("/session-expired"));
    assertTrue(source.contains("LoginUrlAuthenticationEntryPoint"));
    assertTrue(source.contains("failureUrl(\"/session-expired\")"));
  }
}
