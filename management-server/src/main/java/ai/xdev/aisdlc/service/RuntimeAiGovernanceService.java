package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enforces side-effect-free CEL bundles before an external AI action. Any evaluation error is DENY. */
@Service
public class RuntimeAiGovernanceService {
  public record DecisionView(UUID id, String stage, String decision, String reasonCode, String contextSha256, UUID policyEvaluationId) {}
  private final JdbcTemplate jdbc; private final ProjectAccessService access; private final PolicyEvaluationService policies; private final AuditService audit;
  public RuntimeAiGovernanceService(JdbcTemplate jdbc, ProjectAccessService access, PolicyEvaluationService policies, AuditService audit) { this.jdbc=jdbc;this.access=access;this.policies=policies;this.audit=audit; }
  @Transactional
  public DecisionView decide(UUID projectId, String actor, UUID agentSessionId, UUID bundleId, String stage, String requestFingerprint, JsonNode context, boolean dryRun) {
    var project=access.requireMembership(projectId,actor, MembershipRole.OWNER,MembershipRole.DEVELOPER,MembershipRole.REVIEWER); require(stage,"PRE_FLIGHT|POST_FLIGHT|TOOL_CALL|EMERGENCY_OVERRIDE"); require(requestFingerprint,"[a-fA-F0-9]{64}");
    var evaluation=policies.evaluate(projectId,bundleId,actor,context,dryRun); boolean allow=evaluation.outcome()==ai.xdev.aisdlc.domain.DomainTypes.PolicyEvaluationOutcome.PASS && Boolean.TRUE.equals(evaluation.result()); String decision=allow?"ALLOW":"DENY"; String reason=allow?"POLICY_PASS":evaluation.errorCode()==null?"POLICY_FAIL":"POLICY_"+evaluation.errorCode();
    String digest=sha256(context.toString()); UUID id=UUID.randomUUID(); int inserted=jdbc.update("insert into runtime_ai_decisions(id,project_id,agent_session_id,policy_bundle_id,policy_evaluation_id,decision_stage,request_fingerprint,decision,reason_code,context_sha256,decided_by) values(?,?,?,?,?,?,?,?,?,?,?) on conflict(project_id,decision_stage,request_fingerprint) do nothing",id,projectId,agentSessionId,bundleId,evaluation.id(),stage,requestFingerprint.toLowerCase(),decision,reason,digest,actor);
    if(inserted==0)return jdbc.queryForObject("select id,decision_stage,decision,reason_code,context_sha256,policy_evaluation_id from runtime_ai_decisions where project_id=? and decision_stage=? and request_fingerprint=?",(rs,n)->new DecisionView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getObject(6,UUID.class)),projectId,stage,requestFingerprint.toLowerCase());
    audit.append(project.getOrganizationId(),projectId,actor,"runtime_ai.decision", "runtime_ai_decision",id.toString(),"{\"stage\":\""+stage+"\",\"decision\":\""+decision+"\",\"policyEvaluationId\":\""+evaluation.id()+"\"}"); return new DecisionView(id,stage,decision,reason,digest,evaluation.id());
  }
  private static void require(String v,String re){if(v==null||!v.matches(re))throw new IllegalArgumentException("Invalid runtime decision input");} private static String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
