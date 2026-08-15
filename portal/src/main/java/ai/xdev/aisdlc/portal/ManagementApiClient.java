package ai.xdev.aisdlc.portal;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ManagementApiClient {
  private final RestClient rest;
  public ManagementApiClient(@Value("${aisdlc.management-api.base-url}") String baseUrl) { this.rest = RestClient.builder().baseUrl(baseUrl).build(); }

  public List<Map<String, Object>> list(String path, String accessToken) {
    try {
      List<Map<String, Object>> value = rest.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).retrieve().body(new ParameterizedTypeReference<>() {});
      return value == null ? List.of() : value;
    } catch (RuntimeException ignored) { return List.of(); }
  }
  public Map<String, List<Map<String, Object>>> trace(String path, String accessToken) {
    try {
      Map<String, List<Map<String, Object>>> value = rest.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken).retrieve().body(new ParameterizedTypeReference<>() {});
      return value == null ? Map.of("nodes", List.of(), "edges", List.of()) : value;
    } catch (RuntimeException ignored) { return Map.of("nodes", List.of(), "edges", List.of()); }
  }
  public void post(String path, String accessToken, Map<String, Object> payload) {
    rest.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
  }
}
