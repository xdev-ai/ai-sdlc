package ai.xdev.aisdlc.portal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class SessionRecoveryTemplateRenderTest {
  @Test
  void rendersAStaticReauthenticationPathWithoutSensitiveAuthenticationData() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode("HTML"); resolver.setCharacterEncoding("UTF-8");
    SpringTemplateEngine engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);

    String html = engine.process("session-expired", new Context(Locale.ENGLISH));

    assertTrue(html.contains("Keycloak session needs renewal"));
    assertTrue(html.contains("href=\"/oauth2/authorization/keycloak\""));
    assertTrue(html.contains("safe saved workspace request"));
    assertFalse(html.contains("access_token"));
    assertFalse(html.contains("refresh_token"));
    assertFalse(html.contains("client-secret"));
  }
}
