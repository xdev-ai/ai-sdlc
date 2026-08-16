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
  private final JdbcTemplate jdbc; private final ProjectAccessService access; private final AuditService audit; private final NotificationService notifications;
  public BudgetEnforcementService(JdbcTemplate jdbc, ProjectAccessService access, AuditService audit, NotificationService notifications) { this.jdbc=jdbc; this.access=access; this.audit=audit; this.notifications=notifications; }

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
  /**
   * Records one post-ingestion budget decision for a newly persisted usage event. Replayed source
   * events never reach this method; the database uniqueness constraint is a second safety net.
   * A differently denominated event is deliberately not converted or evaluated against this
   * single-currency budget policy.
   */
  @Transactional
  public BudgetDecisionView evaluateAfterUsage(UUID projectId, UUID usageEventId, String usageCurrency, String actor) {
    if (usageEventId == null) throw new IllegalArgumentException("Usage event id is required");
    BudgetPolicyView policy;
    try { policy = policy(projectId); } catch (org.springframework.dao.EmptyResultDataAccessException e) { return null; }
    if (!policy.currencyCode().equals(currency(usageCurrency))) return null;
    return evaluateWithPolicy(projectId, actor, policy, usageEventId);
  }
  private BudgetDecisionView evaluateInternal(UUID projectId,String actor,boolean denyWhenMissing) {
    BudgetPolicyView p; try { p=policy(projectId); } catch (org.springframework.dao.EmptyResultDataAccessException e) { if(!denyWhenMissing)throw e; return recordNoPolicy(projectId,actor); }
    return evaluateWithPolicy(projectId, actor, p, null);
  }
  private BudgetDecisionView evaluateWithPolicy(UUID projectId,String actor,BudgetPolicyView p,UUID usageEventId) {
    LocalDate period=LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1); long spent=jdbc.queryForObject("select coalesce(sum(source_cost_minor),0) from inference_usage_events where project_id=? and currency_code=? and occurred_at>=?",Long.class,projectId,p.currencyCode(),period.atStartOfDay().toInstant(ZoneOffset.UTC)); boolean exception=jdbc.queryForObject("select exists(select 1 from inference_budget_exceptions e join approval_requests a on a.id=e.approval_request_id where e.project_id=? and e.budget_policy_id=? and e.expires_at>=current_date and a.approval_status='APPROVED')",Boolean.class,projectId,p.id()); String decision=exception?"EXCEPTION_APPROVED":spent>=p.monthlyLimitMinor()&&"HOLD".equals(p.enforcementMode())?"HOLD":spent>=p.monthlyLimitMinor()?"WARN":spent*100>=p.monthlyLimitMinor()*p.warningPercent()?"WARN":"ALLOW"; String reason=exception?"HUMAN_APPROVED_EXCEPTION":spent>=p.monthlyLimitMinor()?"BUDGET_LIMIT_REACHED":decision.equals("WARN")?"BUDGET_WARNING_THRESHOLD":"WITHIN_BUDGET"; return record(projectId,p,period,spent,decision,reason,actor,usageEventId);
  }
  private BudgetDecisionView recordNoPolicy(UUID projectId,String actor){ UUID id=UUID.randomUUID(); String evidence=sha256(projectId+"|NO_POLICY|"+LocalDate.now(ZoneOffset.UTC)); jdbc.update("insert into inference_budget_decisions(id,budget_policy_id,project_id,period_start,currency_code,spent_minor,limit_minor,decision,reason_code,evidence_sha256,decided_by,usage_event_id) values(?,null,?,current_date,'N/A',0,1,'DENY_NO_POLICY','NO_ACTIVE_BUDGET_POLICY',?,?,null)",id,projectId,evidence,actor); return new BudgetDecisionView(id,"DENY_NO_POLICY","NO_ACTIVE_BUDGET_POLICY",0,0,"N/A"); }
  private BudgetDecisionView record(UUID projectId,BudgetPolicyView p,LocalDate period,long spent,String decision,String reason,String actor,UUID usageEventId){ UUID id=UUID.randomUUID(); String ev=sha256(projectId+"|"+p.id()+"|"+period+"|"+spent+"|"+decision+"|"+(usageEventId==null?"manual":usageEventId)); int inserted=jdbc.update("insert into inference_budget_decisions(id,budget_policy_id,project_id,period_start,currency_code,spent_minor,limit_minor,decision,reason_code,evidence_sha256,decided_by,usage_event_id) values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict(usage_event_id) where usage_event_id is not null do nothing",id,p.id(),projectId,period,p.currencyCode(),spent,p.monthlyLimitMinor(),decision,reason,ev,actor,usageEventId); if(inserted==0&&usageEventId!=null)return decisionForUsage(usageEventId); BudgetDecisionView view=new BudgetDecisionView(id,decision,reason,spent,p.monthlyLimitMinor(),p.currencyCode()); queueThresholdNotification(projectId,period,view); return view; }
  private BudgetDecisionView decisionForUsage(UUID usageEventId){return jdbc.queryForObject("select id,decision,reason_code,spent_minor,limit_minor,currency_code from inference_budget_decisions where usage_event_id=?",(rs,n)->new BudgetDecisionView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getLong(5),rs.getString(6)),usageEventId);}
  private void queueThresholdNotification(UUID projectId,LocalDate period,BudgetDecisionView view){if(!"WARN".equals(view.decision())&&!"HOLD".equals(view.decision()))return; String key="inference-budget:"+projectId+":"+period+":"+view.decision()+":"+view.reasonCode(); String subject="AI-SDLC inference budget "+(view.decision().equals("HOLD")?"hold":"warning"); String body="Project inference spend is "+view.spentMinor()+" "+view.currencyCode()+" minor units against a "+view.limitMinor()+" minor-unit monthly limit. Decision: "+view.decision()+" ("+view.reasonCode()+"). Human approval is required for any exception."; notifications.queueProjectNotification(projectId,"inference_budget.threshold",subject,body,key);}
  private BudgetPolicyView policy(UUID projectId){return jdbc.queryForObject("select id,currency_code,calendar_month_limit_minor,warning_percent,enforcement_mode,active from inference_budget_policies where project_id=? and active=true",(rs,n)->new BudgetPolicyView(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getInt(4),rs.getString(5),rs.getBoolean(6)),projectId);}
  private static String currency(String v){if(v==null||!v.matches("[A-Za-z]{3}"))throw new IllegalArgumentException("ISO currency code required");return v.toUpperCase(Locale.ROOT);} private static void digest(String v){if(v==null||!v.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("SHA-256 required");} private static String sha256(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
