package ai.xdev.aisdlc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JsonConfigurationTest {
  @Test
  void serializesInstantsAsIso8601Strings() throws Exception {
    String json = new JsonConfiguration().objectMapper().writeValueAsString(Instant.parse("2026-08-16T06:00:00Z"));
    assertEquals("\"2026-08-16T06:00:00Z\"", json);
  }
}
