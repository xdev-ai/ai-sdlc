package ai.xdev.aisdlc.web;

import ai.xdev.aisdlc.domain.DomainTypes.*;
import ai.xdev.aisdlc.service.ApprovalOrchestrationService;
import ai.xdev.aisdlc.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class NotificationApprovalController {
  record Created(UUID id) {}
  record NotificationChannelInput(@NotNull NotificationChannelType type, @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 2000) String destination, @Size(max = 2000) String sharedSecret) {}
  record ChannelEnabledInput(boolean enabled) {}
  record ApprovalInput(@NotBlank @Size(max = 80) String sourceType, @Size(max = 200) String sourceId, @NotBlank @Size(max = 300) String title, @Size(max = 24000) String details, @Min(1) @Max(50) int requiredQuorum, @Size(max = 200) String requestedApproverSubject, @NotNull Instant dueAt) {}
  record DecisionInput(@NotNull ApprovalDecisionType decision, @Size(max = 24_000) String comment) {}
  record DelegateInput(@NotBlank @Size(max = 200) String delegateSubject) {}
  record SecurityExceptionInput(@NotBlank @Size(max = 300) String sourceReference, @NotBlank @Size(max = 24000) String justification, @NotNull Instant expiresAt) {}
  private final NotificationService notifications; private final ApprovalOrchestrationService approvals;
  public NotificationApprovalController(NotificationService notifications, ApprovalOrchestrationService approvals) { this.notifications = notifications; this.approvals = approvals; }
  @PostMapping("/projects/{projectId}/notification-channels") @ResponseStatus(HttpStatus.CREATED)
  Created createChannel(@PathVariable UUID projectId, @RequestBody @Valid NotificationChannelInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(notifications.createChannel(projectId, jwt.getSubject(), input.type(), input.name(), input.destination(), input.sharedSecret())); }
  @GetMapping("/projects/{projectId}/notification-channels")
  List<Map<String, Object>> channels(@PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) { return notifications.listChannels(projectId, jwt.getSubject()); }
  @PatchMapping("/projects/{projectId}/notification-channels/{channelId}") @ResponseStatus(HttpStatus.NO_CONTENT)
  void setChannelEnabled(@PathVariable UUID projectId, @PathVariable UUID channelId, @RequestBody @Valid ChannelEnabledInput input, @AuthenticationPrincipal Jwt jwt) { notifications.setChannelEnabled(projectId, channelId, input.enabled(), jwt.getSubject()); }
  @GetMapping("/projects/{projectId}/notification-deliveries")
  PageResponse<Map<String, Object>> deliveries(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return notifications.listDeliveries(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/projects/{projectId}/approvals") @ResponseStatus(HttpStatus.CREATED)
  Created createApproval(@PathVariable UUID projectId, @RequestBody @Valid ApprovalInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(approvals.requestApproval(projectId, jwt.getSubject(), input.sourceType(), input.sourceId(), input.title(), input.details(), input.requiredQuorum(), input.requestedApproverSubject(), input.dueAt())); }
  @GetMapping("/projects/{projectId}/approvals")
  PageResponse<Map<String, Object>> approvals(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return approvals.list(projectId, jwt.getSubject(), page, size); }
  @PostMapping("/approvals/{approvalId}/decisions") @ResponseStatus(HttpStatus.NO_CONTENT)
  void decide(@PathVariable UUID approvalId, @RequestBody @Valid DecisionInput input, @AuthenticationPrincipal Jwt jwt) { approvals.decide(approvalId, jwt.getSubject(), input.decision(), input.comment()); }
  @PostMapping("/approvals/{approvalId}/delegation") @ResponseStatus(HttpStatus.NO_CONTENT)
  void delegate(@PathVariable UUID approvalId, @RequestBody @Valid DelegateInput input, @AuthenticationPrincipal Jwt jwt) { approvals.delegate(approvalId, jwt.getSubject(), input.delegateSubject()); }
  @PostMapping("/projects/{projectId}/security-exceptions") @ResponseStatus(HttpStatus.CREATED)
  Created createSecurityException(@PathVariable UUID projectId, @RequestBody @Valid SecurityExceptionInput input, @AuthenticationPrincipal Jwt jwt) { return new Created(approvals.createSecurityException(projectId, jwt.getSubject(), input.sourceReference(), input.justification(), input.expiresAt())); }
  @GetMapping("/projects/{projectId}/security-exceptions")
  PageResponse<Map<String, Object>> securityExceptions(@PathVariable UUID projectId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @AuthenticationPrincipal Jwt jwt) { return approvals.listSecurityExceptions(projectId, jwt.getSubject(), page, size); }
}
