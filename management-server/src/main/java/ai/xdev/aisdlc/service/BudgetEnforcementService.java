package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.domain.DomainTypes.MembershipRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped inference-budget control. It only advises or holds a governed action; it never changes provider routing. */
@Service
public class BudgetEnforcementService {
  public record BudgetPolicyView(UUID id, String currencyCode, long monthlyLimitMinor, int warningPercent, String enforcementMode, boolean active) {}
  public record BudgetDecisionView(UUID id, String decision, String reasonCode, long spentMinor, long limitMinor, String currencyCode) { public boolean allowed() { return "ALLOW".equals(decision) || "WARN".equals(decision) || "EXCEPTION_APPROVED".equals(decision); } }
  private final JdbcTemplate jdbc; private final ProjectAccessService access; private final AuditService audit;
  public BudgetEnforcementService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit) { this.jdbc=jdbc; this.access=access; this.audit=audit; }

  @Transactional
  public BudgetPolicyView configure(UUID projectId, String actor, String currency, long monthlyLimitMinor, int warningPercent, String mode) {
    var project=access.requireMembership(projectId,actor,MembershipRole.OWNER); String c=currency(currency); if(monthlyLimitMinor<=0||warningPercent<1||warningPercent>99)throw new IllegalArgumentException("Invalid budget threshold"); if(!"ADVISORY".equals(mode)&&!"HOLD".equals(mode))throw new IllegalArgumentException("Invalid enforcement mode");
    UUID id=UUID.randomUUID(); jdbc.update("insert into inference_budget_policies(id,project_id,currency_code,calendar_month_limit_minor,warning_percent,enforcement_mode,created_by) values(?,?,?,?,?,?,?) on conflict(project_id) do update set currency_code=excluded.currency_code,calendar_month_limit_minor=excluded.calendar_month_limit_minor,warning_percent=excluded.warning_percent,enforcement_mode=excluded.enforcement_mode,active=true,updated_at=now()",id,projectId,c,monthlyLimitMinor,warningPercent,mode,actor);
    var view=policy(projectId); audit.append(project.getOrganizationId(),projectId,actor,"inference_budget.configured","inference_budget_policy",view.id().toString(),"{\"currency\":\""+c+"\",\"limitMinor\":"+monthlyLimitMinor+"}"); return view;
  }
  @Transactional
  public void requestException(UUID projectId,String actor,UUID approvalRequestId,LocalDate expiresAt,String rationaleSha256) {
    var project=access.requireMembership(projectId,actor,MembershipRole.OWNER,MembershipRole.DEVELOPER); if(expiresAt==null||!expiresAt.isAfter(LocalDate.now(ZoneOffset.UTC)))throw new IllegalArgumentException("Future expiry required"); digest(rationaleSha256); var p=policy(projectId); jdbc.update("insert into inference_budget_exceptions(id,budget_policy_id,project_id,approval_request_id,requested_by,expires_at,rationale_sha256) values(?,?,?,?,?,?,?) on conflict(budget_policy_id,approval_request_id) do nothing",UUID.randomUUID(),p.id(),projectId,approvalRequestId,actor,expiresAt,rationaleSha256.toLowerCase(Locale.ROOT)); audit.append(project.getOrganizationId(),projectId,actor,"inference_budget.exception_requested","approval_request",approvalRequestId.toString(),"{\"expiresAt\":\""+expiresAt+"\"}");
  }
  @Transactional public BudgetDecisionView evaluate(UUID projectId,String actor){ access.requireMembership(projectId,actor,MembershipRole.OWNER,MembershipRole.DEVELOPER,MembershipRole.REVIEWER); return evaluateInternal(projectId,actor,false); }
  @Transactional public BudgetDecisionView evaluateForRuntime(UUID projectId,String workloadSubject){ return evaluateInternal(projectId,workloadSubject,true); }
  private BudgetDecisionView evaluateInternal(UUID projectId,String actor,boolean denyWhenMissing) {
    BudgetPolicyView p; try { p=policy(projectId); } catch (org.springframework.dao.EmptyResultDataAccessException e) { if(!denyWhenMissing)throw e; return recordNoPolicy(projectId,actor); }
    LocalDate period=LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1); long spent=jdbc.queryForObject("select coalesce(sum(source_cost_minor),0) from inference_usage_events where project_id=? and currency_code=? and occurred_at>=?",Long.class,projectId,p.currencyCode(),period.atStartOfDay().toInstant(ZoneOffset.UTC)); boolean exception=jdbc.queryForObject("select exists(select 1 from inference_budget_exceptions e join approval_requests a on a.id=e.approval_request_id where e.project_id=? and e.budget_policy_id=? and e.expires_at>=current_date and a.approval_status='APPROVED')",Boolean.class,projectId,p.id()); String decision=exception?"EXCEPTION_APPROVED":spent>=p.monthlyLimitMinor()&&"HOLD".equals(p.enforcementMode())?"HOLD":spent>=p.monthlyLimitMinor()?"WARN":spent*100>=p.monthlyLimitMinor()*p.warningPercent()?"WARN":"ALLOW"; String reason=exception?"HUMAN_APPROVED_EXCEPTION":spent>=p.monthlyLimitMinor()?"BUDGET_LIMIT_REACHED":decision.equals("WARN")?"BUDGET_WARNING_THRESHOLD":"WITHIN_BUDGET"; return record(projectId,p,period,spent,decision,reason,actor);
  }
  private BudgetDecisionView recordNoPolicy(UUID projectId,String actor){ UUID id=UUID.randomUUID(); String evidence=sha256(projectId+"|NO_POLICY|"+LocalDate.now(ZoneOffset.UTC)); jdbc.update("insert into inference_budget_decisions(id,budget_policy_id,project_id,period_start,currency_code,spent_minor,limit_minor,decision,reason_code,evidence_sha256,decided_by) values(?,null,?,current_date,'N/A',0,1,'DENY_NO_POLICY','NO_ACTIVE_BUDGET_POLICY',?,?)",id,projectId,evidence,actor); return new BudgetDecisionView(id,"DENY_NO_POLICY","NO_ACTIVE_BUDGET_POLICY",0,0,"N/A"); }
  private BudgetDecisionView record(UUID projectId,BudgetPolicyView p,LocalDate period,long spent,String decision,String reason,String actor){ UUID id=UUID.randomUUID(); String ev=sha256(projectId+"|"+p.id()+"|"+period+"|"+spent+"|"+decision); jdbc.update("insert into inference_budget_decisions(id,budget_policy_id,project_id,period_start,currency_code,spent_minor,limit_minor,decision,reason_code,evidence_sha256,decided_by) values(?,?,?,?,?,?,?,?,?,?,?)",id,p.id(),projectId,period,p.currencyCode(),spent,p.monthlyLimitMinor(),decision,reason,ev,actor); return new BudgetDecisionView(id,decision,reason,spent,p.monthlyLimitMinor(),p.currencyCode()); }
  private BudgetPolicyView policy(UUID projectId){return jdbc.queryForObject("select id,currency_code,calendar_month_limit_minor,warning_percent,enforcement_mode,active from inference_budget_policies where project_id=? and active=true",(rs,n)->new BudgetPolicyView(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getInt(4),rs.getString(5),rs.getBoolean(6)),projectId);}
  private static String currency(String v){if(v==null||!v.matches("[A-Za-z]{3}"))throw new IllegalArgumentException("ISO currency code required");return v.toUpperCase(Locale.ROOT);} private static void digest(String v){if(v==null||!v.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("SHA-256 required");} private static String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
