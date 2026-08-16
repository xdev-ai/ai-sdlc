package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.service.EnterpriseTenantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants")
public class EnterpriseTenantController {
  private final EnterpriseTenantService service;
  public EnterpriseTenantController(EnterpriseTenantService service) { this.service = service; }
  public record TenantInput(@NotBlank @Pattern(regexp = "[a-z0-9-]{3,80}") String slug, @NotBlank @Size(max = 160) String displayName, @NotBlank @Size(max = 80) String dataResidency, @Size(max = 300) String encryptionKeyReference) {}
  public record MembershipInput(@NotBlank @Size(max = 200) String subject, @NotNull TenantRole role) {}
  public record PermissionSetInput(@NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{2,100}") String key, @NotBlank @Size(max = 160) String displayName, @NotEmpty @Size(max = 100) List<@NotBlank String> permissions) {}
  public record FederationInput(@NotNull FederationProtocol protocol, @NotBlank @Size(max = 500) String issuerUri, @Size(max = 300) String clientId, @Size(max = 2000) String clientSecret, @Size(max = 500) String metadataUri, Map<String, Object> claimMapping, boolean enabled) {}
  public record ScimPrincipalInput(@NotBlank @Size(max = 160) String displayName) {}
  public record LegalHoldInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{3,120}") String holdKey, @NotBlank @Size(max = 4000) String reason) {}

  @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('admin')")
  EnterpriseTenantService.TenantView create(@RequestBody @Valid TenantInput input, @AuthenticationPrincipal Jwt jwt) { return service.create(input.slug(), input.displayName(), input.dataResidency(), input.encryptionKeyReference(), jwt.getSubject()); }
  @GetMapping("/{tenantId}") EnterpriseTenantService.TenantView get(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) { return service.get(tenantId, jwt.getSubject()); }
  @GetMapping("/{tenantId}/memberships") List<EnterpriseTenantService.MembershipView> memberships(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) { return service.memberships(tenantId, jwt.getSubject()); }
  @PostMapping("/{tenantId}/memberships") @ResponseStatus(HttpStatus.CREATED) EnterpriseTenantService.MembershipView assignMembership(@PathVariable UUID tenantId, @RequestBody @Valid MembershipInput input, @AuthenticationPrincipal Jwt jwt) { return service.assignMembership(tenantId, input.subject(), input.role(), jwt.getSubject()); }
  @GetMapping("/{tenantId}/permission-sets") List<EnterpriseTenantService.PermissionSetView> permissionSets(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) { return service.permissionSets(tenantId, jwt.getSubject()); }
  @PostMapping("/{tenantId}/permission-sets") @ResponseStatus(HttpStatus.CREATED) EnterpriseTenantService.PermissionSetView createPermissionSet(@PathVariable UUID tenantId, @RequestBody @Valid PermissionSetInput input, @AuthenticationPrincipal Jwt jwt) { return service.createPermissionSet(tenantId, input.key(), input.displayName(), input.permissions(), jwt.getSubject()); }
  @GetMapping("/{tenantId}/federation-configs") List<EnterpriseTenantService.FederationView> federations(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) { return service.federations(tenantId, jwt.getSubject()); }
  @PostMapping("/{tenantId}/federation-configs") @ResponseStatus(HttpStatus.CREATED) EnterpriseTenantService.FederationView configureFederation(@PathVariable UUID tenantId, @RequestBody @Valid FederationInput input, @AuthenticationPrincipal Jwt jwt) { return service.configureFederation(tenantId, input.protocol(), input.issuerUri(), input.clientId(), input.clientSecret(), input.metadataUri(), input.claimMapping(), input.enabled(), jwt.getSubject()); }
  @PostMapping("/{tenantId}/scim-service-principals") @ResponseStatus(HttpStatus.CREATED) EnterpriseTenantService.ScimCredentialView createScimPrincipal(@PathVariable UUID tenantId, @RequestBody @Valid ScimPrincipalInput input, @AuthenticationPrincipal Jwt jwt) { return service.createScimPrincipal(tenantId, input.displayName(), jwt.getSubject()); }
  @GetMapping("/{tenantId}/legal-holds") List<EnterpriseTenantService.LegalHoldView> legalHolds(@PathVariable UUID tenantId, @AuthenticationPrincipal Jwt jwt) { return service.legalHolds(tenantId, jwt.getSubject()); }
  @PostMapping("/{tenantId}/legal-holds") @ResponseStatus(HttpStatus.CREATED) EnterpriseTenantService.LegalHoldView createLegalHold(@PathVariable UUID tenantId, @RequestBody @Valid LegalHoldInput input, @AuthenticationPrincipal Jwt jwt) { return service.createLegalHold(tenantId, input.holdKey(), input.reason(), jwt.getSubject()); }
  @PostMapping("/{tenantId}/legal-holds/{holdId}/release") EnterpriseTenantService.LegalHoldView releaseLegalHold(@PathVariable UUID tenantId, @PathVariable UUID holdId, @AuthenticationPrincipal Jwt jwt) { return service.releaseLegalHold(tenantId, holdId, jwt.getSubject()); }
  @GetMapping("/{tenantId}/e-discovery-exports") List<EnterpriseTenantService.EDiscoveryExportView> exports(@PathVariable UUID tenantId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return service.exports(tenantId, jwt.getSubject(), page, size); }
  @PostMapping("/{tenantId}/e-discovery-exports") @ResponseStatus(HttpStatus.CREATED) EnterpriseTenantService.EDiscoveryExportView createExport(@PathVariable UUID tenantId, @RequestBody(required = false) Map<String, Object> scope, @AuthenticationPrincipal Jwt jwt) { return service.createExport(tenantId, scope, jwt.getSubject()); }
}
