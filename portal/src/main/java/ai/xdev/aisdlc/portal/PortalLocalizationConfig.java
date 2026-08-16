package ai.xdev.aisdlc.portal;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/** Locale contract for the presentation layer only; API payloads remain language-neutral. */
@Configuration
public class PortalLocalizationConfig implements WebMvcConfigurer {
  public static final String LOCALE_PARAMETER = "lang";
  public static final String LOCALE_COOKIE = "AISDLC_LOCALE";
  private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
  private static final Set<String> SUPPORTED_LANGUAGE_TAGS = Set.of("en", "vi");

  @Bean
  LocaleResolver localeResolver() {
    CookieLocaleResolver resolver = new CookieLocaleResolver(LOCALE_COOKIE) {
      @Override
      public Locale resolveLocale(HttpServletRequest request) {
        Locale resolved = super.resolveLocale(request);
        return isSupported(resolved) ? resolved : DEFAULT_LOCALE;
      }
    };
    resolver.setDefaultLocale(DEFAULT_LOCALE);
    resolver.setCookieMaxAge(Duration.ofDays(365));
    resolver.setCookiePath("/");
    resolver.setLanguageTagCompliant(true);
    return resolver;
  }

  @Bean
  LocaleChangeInterceptor localeChangeInterceptor() {
    LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor() {
      @Override
      protected Locale parseLocaleValue(String localeValue) {
        Locale parsed = super.parseLocaleValue(localeValue);
        return isSupported(parsed) ? parsed : DEFAULT_LOCALE;
      }
    };
    interceptor.setParamName(LOCALE_PARAMETER);
    interceptor.setHttpMethods("GET");
    return interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(localeChangeInterceptor()).addPathPatterns("/**");
  }

  private static boolean isSupported(Locale locale) {
    return locale != null && SUPPORTED_LANGUAGE_TAGS.contains(locale.toLanguageTag());
  }
}
