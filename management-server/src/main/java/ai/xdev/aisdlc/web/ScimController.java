package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.service.EnterpriseTenantService;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/scim/v2/tenants/{tenantId}/Users", produces = "application/scim+json")
public class ScimController {
  private final EnterpriseTenantService service;
  public ScimController(EnterpriseTenantService service) { this.service = service; }
  @GetMapping
  Map<String, Object> list(@PathVariable UUID tenantId, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam(defaultValue = "1") int startIndex, @RequestParam(defaultValue = "100") int count) {
    authorize(tenantId, authorization); List<EnterpriseTenantService.ScimUserView> users = service.scimUsers(tenantId, Math.max(0, startIndex - 1) / Math.max(1, count), count); return Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:ListResponse"), "totalResults", users.size(), "startIndex", startIndex, "itemsPerPage", users.size(), "Resources", users.stream().map(this::resource).toList());
  }
  @PostMapping(consumes = "application/scim+json", produces = "application/scim+json") @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> create(@PathVariable UUID tenantId, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> body) {
    authorize(tenantId, authorization); String userName = required(body, "userName"); String externalId = string(body.get("externalId")); String displayName = string(body.get("displayName")); boolean active = !(body.get("active") instanceof Boolean b) || b; String subject = externalId == null || externalId.isBlank() ? "scim:" + userName.toLowerCase(Locale.ROOT) : externalId; EnterpriseTenantService.ScimUserView user = service.provisionScimUser(tenantId, externalId, subject, userName, displayName, active, body); return resource(user);
  }
  private void authorize(UUID tenantId, String authorization) { if (authorization == null || !authorization.startsWith("Bearer ") || !service.authorizeScim(tenantId, authorization.substring(7))) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "SCIM bearer token is invalid"); }
  private Map<String, Object> resource(EnterpriseTenantService.ScimUserView user) { return Map.of("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"), "id", user.id().toString(), "externalId", Objects.toString(user.externalId(), ""), "userName", user.userName(), "displayName", Objects.toString(user.displayName(), ""), "active", user.active(), "meta", Map.of("resourceType", "User")); }
  private static String required(Map<String, Object> body, String key) { String value = string(body.get(key)); if (value == null || value.isBlank()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required"); return value; }
  private static String string(Object value) { return value == null ? null : String.valueOf(value); }
}
