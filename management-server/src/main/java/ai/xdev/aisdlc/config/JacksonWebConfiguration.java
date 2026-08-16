package ai.xdev.aisdlc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Binds the web layer to the application's Jackson 2 {@link ObjectMapper}.
 *
 * <p>Spring Boot 4 registers a Jackson 3 converter by default. Every service and controller here models JSON with
 * {@code com.fasterxml.jackson.databind.JsonNode}, and the Jackson 3 converter cannot bind that type: any request or
 * response carrying a {@code JsonNode} failed with
 * {@code HttpMessageConversionException: Type definition error: [simple type, class ...JsonNode]}.
 *
 * <p>That silently disabled a whole family of endpoints — policy bundles and evaluation, runtime AI governance, the
 * provider proxy, the tool broker, and SCM repository registration. Unit tests could not see it because they call
 * controller methods directly and never cross an HTTP message converter.
 *
 * <p>Placing the Jackson 2 converter first restores the mapper the application already configures in
 * {@link JsonConfiguration}, rather than leaving two mappers disagreeing about which types exist.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonWebConfiguration implements WebMvcConfigurer {
  private final ObjectMapper objectMapper;

  public JacksonWebConfiguration(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    // Insert immediately before the Jackson 3 converter, not at the head of the list. At index 0 this converter also
    // claims application/json for byte[], and the GitHub webhook endpoint takes the raw body as byte[] so it can
    // verify an HMAC over the exact bytes received. Deserialising those bytes as JSON breaks signature verification.
    int jacksonIndex = converters.size();
    for (int index = 0; index < converters.size(); index++) {
      if (converters.get(index).getClass().getName().contains("Jackson")) {
        jacksonIndex = index;
        break;
      }
    }
    converters.add(jacksonIndex, new MappingJackson2HttpMessageConverter(objectMapper));
  }
}
