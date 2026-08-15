package ai.xdev.aisdlc.portal;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Server-side OAuth client. Browser code never receives a management API access token. */
@Component
public class ManagementApiClient {
  private final RestClient rest;
  public ManagementApiClient(@Value("${aisdlc.management-api.base-url}") String baseUrl) { this.rest = RestClient.builder().baseUrl(baseUrl).build(); }

  public record PageData(List<Map<String, Object>> items, int page, int size, long totalItems, int totalPages, String error) {
    public static PageData empty() { return new PageData(List.of(), 0, 25, 0, 0, null); }
    public boolean hasError() { return error != null; }
  }
  public record ListData(List<Map<String, Object>> items, String error) {
    public static ListData empty() { return new ListData(List.of(), null); }
    public boolean hasError() { return error != null; }
  }
  public record ObjectData(Map<String, Object> value, String error) {
    public static ObjectData empty() { return new ObjectData(Map.of(), null); }
    public boolean hasError() { return error != null; }
  }

  public PageData page(String path, String accessToken) {
    try {
      Map<String, Object> body = rest.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).retrieve().body(new ParameterizedTypeReference<>() {});
      if (body == null) return PageData.empty();
      return new PageData(maps(body.get("items")), number(body.get("page")), number(body.get("size")), longNumber(body.get("totalItems")), number(body.get("totalPages")), null);
    } catch (RuntimeException error) { return new PageData(List.of(), 0, 25, 0, 0, errorMessage(error)); }
  }

  public ListData list(String path, String accessToken) {
    try {
      List<Map<String, Object>> value = rest.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).retrieve().body(new ParameterizedTypeReference<>() {});
      return new ListData(value == null ? List.of() : value, null);
    } catch (RuntimeException error) { return new ListData(List.of(), errorMessage(error)); }
  }

  public ObjectData object(String path, String accessToken) {
    try {
      Map<String, Object> value = rest.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).retrieve().body(new ParameterizedTypeReference<>() {});
      return new ObjectData(value == null ? Map.of() : value, null);
    } catch (RuntimeException error) { return new ObjectData(Map.of(), errorMessage(error)); }
  }

  public ObjectData trace(String path, String accessToken) { return object(path, accessToken); }
  public String post(String path, String accessToken, Map<String, Object> payload) { return mutate(path, accessToken, payload, "POST"); }
  public String put(String path, String accessToken, Map<String, Object> payload) { return mutate(path, accessToken, payload, "PUT"); }
  public String delete(String path, String accessToken) { return mutate(path, accessToken, Map.of(), "DELETE"); }

  private String mutate(String path, String accessToken, Map<String, Object> payload, String method) {
    try {
      var request = rest.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken));
      if ("DELETE".equals(method)) request.retrieve().toBodilessEntity();
      else request.contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
      return null;
    } catch (RuntimeException error) { return errorMessage(error); }
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> maps(Object value) { return value instanceof List<?> list ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList() : List.of(); }
  private int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
  private long longNumber(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
  private String bearer(String token) { return "Bearer " + token; }
  private String errorMessage(RuntimeException error) {
    if (error instanceof RestClientResponseException response) return "Control plane returned " + response.getStatusCode().value() + ". Check role, scope, and request data.";
    return "Control plane is temporarily unavailable. Please retry.";
  }
}
