package ai.xdev.aisdlc.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;

class PortalLocalizationConfigTest {
  private final PortalLocalizationConfig config = new PortalLocalizationConfig();

  @Test
  void defaultsToEnglishAndPersistsVietnamesePreference() {
    LocaleResolver resolver = config.localeResolver();
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    resolver.setLocale(request, response, Locale.forLanguageTag("vi"));

    assertThat(response.getCookie(PortalLocalizationConfig.LOCALE_COOKIE)).isNotNull();
    assertThat(response.getCookie(PortalLocalizationConfig.LOCALE_COOKIE).getValue()).isEqualTo("vi");
  }

  @Test
  void fallsBackToEnglishForUnsupportedCookieLocale() {
    LocaleResolver resolver = config.localeResolver();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(PortalLocalizationConfig.LOCALE_COOKIE, "fr"));

    assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
  }

  @Test
  void normalizesUnsupportedQueryLocaleToEnglish() throws Exception {
    LocaleResolver resolver = config.localeResolver();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addParameter(PortalLocalizationConfig.LOCALE_PARAMETER, "fr");
    request.setAttribute(DispatcherServlet.LOCALE_RESOLVER_ATTRIBUTE, resolver);

    config.localeChangeInterceptor().preHandle(request, response, new Object());

    assertThat(response.getCookie(PortalLocalizationConfig.LOCALE_COOKIE)).isNotNull();
    assertThat(response.getCookie(PortalLocalizationConfig.LOCALE_COOKIE).getValue()).isEqualTo("en");
  }
}
