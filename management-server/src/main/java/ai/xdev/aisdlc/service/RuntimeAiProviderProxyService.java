package ai.xdev.aisdlc.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Internal provider adapter. It executes only a broker-authorized request and stores digest-only
 * dispatch evidence. The default credential resolver blocks all egress until a deployment binds a
 * secret-manager-backed resolver.
 */
@Service
public class RuntimeAiProviderProxyService {
  public record InvocationRequest(UUID agentSessionId, String provider, String model, String requestFingerprint, JsonNode policyContext, UUID idempotencyKey, JsonNode payload) {}
  public record InvocationResult(String outcome, String reasonCode, Integer httpStatus, int attempts, String requestSha256, String responseSha256, String responseBody, UUID runtimeDecisionId) {}
  record Profile(UUID id, URI endpoint, String credentialReference, boolean requireMtls, String mtlsReference, int timeoutMs, int maxAttempts) {}
  private final JdbcTemplate jdbc; private final RuntimeAiBrokerService broker; private final ProjectAccessService access; private final AuditService audit; private final ProviderCredentialResolver credentials; private final ProviderHttpTransport transport; private final org.springframework.beans.factory.ObjectProvider<ChaosFaultRegistry> chaosFaults;
  public RuntimeAiProviderProxyService(JdbcTemplate jdbc, RuntimeAiBrokerService broker, ProjectAccessService access, AuditService audit, ProviderCredentialResolver credentials, ProviderHttpTransport transport) { this(jdbc,broker,access,audit,credentials,transport,null); }
  @org.springframework.beans.factory.annotation.Autowired public RuntimeAiProviderProxyService(JdbcTemplate jdbc, RuntimeAiBrokerService broker, ProjectAccessService access, AuditService audit, ProviderCredentialResolver credentials, ProviderHttpTransport transport, org.springframework.beans.factory.ObjectProvider<ChaosFaultRegistry> chaosFaults) { this.jdbc=jdbc; this.broker=broker; this.access=access; this.audit=audit; this.credentials=credentials; this.transport=transport; this.chaosFaults=chaosFaults; }

  public InvocationResult invoke(UUID projectId, String workloadSubject, InvocationRequest request) {
    validate(request); String body=request.payload().toString(); String requestDigest=sha256(body);
    var authorization=broker.preflight(projectId,workloadSubject,request.agentSessionId(),request.provider(),request.model(),request.requestFingerprint(),request.policyContext(),false);
    if (!authorization.allowed()) return new InvocationResult("BLOCKED",authorization.reasonCode(),null,0,requestDigest,null,null,authorization.runtimeDecisionId());
    Profile profile; try { profile=profile(projectId,request.provider(),request.model()); } catch (RuntimeException e) { return new InvocationResult("BLOCKED","PROVIDER_PROFILE_UNAVAILABLE",null,0,requestDigest,null,null,authorization.runtimeDecisionId()); }
    UUID dispatchId=UUID.randomUUID(); int inserted=jdbc.update("insert into runtime_ai_provider_dispatches(id,project_id,provider_profile_id,runtime_decision_id,agent_session_id,idempotency_key,request_fingerprint,request_sha256,dispatch_status,reason_code,attempts) values(?,?,?,?,?,?,?,?, 'IN_FLIGHT','PRE_FLIGHT_ALLOWED',0) on conflict(project_id,idempotency_key) do nothing",dispatchId,projectId,profile.id(),authorization.runtimeDecisionId(),request.agentSessionId(),request.idempotencyKey(),request.requestFingerprint(),requestDigest);
    if(inserted==0)return new InvocationResult("BLOCKED","DUPLICATE_REQUEST",null,0,requestDigest,null,null,authorization.runtimeDecisionId());
    ProviderCredentialResolver.CredentialMaterial material; try { material=credentials.resolve(profile.credentialReference(),profile.mtlsReference(),profile.requireMtls()); if(material==null||blank(material.authorizationHeader())||(profile.requireMtls()&&material.sslContext()==null)) throw new IllegalStateException("Credential material unavailable"); } catch(RuntimeException e) { return finish(projectId,workloadSubject,dispatchId,profile,authorization.runtimeDecisionId(),"FAILED",profile.requireMtls()?"PROVIDER_MTLS_UNAVAILABLE":"PROVIDER_CREDENTIAL_UNAVAILABLE",null,0,requestDigest,null,null); }
    ProviderHttpTransport.Response response=null; String reason="PROVIDER_TRANSPORT_FAILURE"; int attempts=0;
    for (int attempt = 1; attempt <= profile.maxAttempts(); attempt++) {
      attempts = attempt;
      // Clear the previous attempt's response: a later timeout or transport failure must report its own terminal
      // reason, not be masked by a retryable response captured earlier in the loop.
      response = null;
      try {
        if (chaosFaults != null) { chaosFaults.ifAvailable(registry -> registry.check(ChaosFaultRegistry.Component.RUNTIME_AI_PROVIDER)); }
        response = transport.execute(new ProviderHttpTransport.Request(profile.endpoint(), body, material.authorizationHeader(), request.idempotencyKey().toString(), Duration.ofMillis(profile.timeoutMs()), material.sslContext()));
        if (!retryable(response.statusCode()) || attempt == profile.maxAttempts()) { break; }
        reason = "PROVIDER_RETRY_EXHAUSTED";
        pause(attempt);
      } catch (java.net.http.HttpTimeoutException e) {
        reason = "PROVIDER_TIMEOUT";
        if (attempt < profile.maxAttempts()) { pause(attempt); continue; }
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
        reason = "PROVIDER_TRANSPORT_FAILURE";
        if (attempt < profile.maxAttempts() && !Thread.currentThread().isInterrupted()) { pause(attempt); continue; }
        break;
      } catch (ChaosFaultRegistry.ChaosFaultException injected) {
        reason = "PROVIDER_TIMEOUT";
        break;
      } catch (RuntimeException e) {
        reason = "PROVIDER_TRANSPORT_FAILURE";
        break;
      }
    }
    if(response==null)return finish(projectId,workloadSubject,dispatchId,profile,authorization.runtimeDecisionId(),"FAILED",reason,null,attempts,requestDigest,null,null);
    String responseDigest=sha256(response.body()==null?"":response.body()); boolean success=response.statusCode()>=200&&response.statusCode()<300; String outcome=success?"COMPLETE":"FAILED"; String responseReason=success?"PROVIDER_RESPONSE_ACCEPTED":retryable(response.statusCode())?"PROVIDER_RETRY_EXHAUSTED":"PROVIDER_HTTP_"+response.statusCode();
    return finish(projectId,workloadSubject,dispatchId,profile,authorization.runtimeDecisionId(),outcome,responseReason,response.statusCode(),attempts,requestDigest,responseDigest,response.body());
  }

  private InvocationResult finish(UUID projectId,String subject,UUID dispatchId,Profile profile,UUID runtimeDecisionId,String outcome,String reason,Integer httpStatus,int attempts,String requestDigest,String responseDigest,String body){ jdbc.update("update runtime_ai_provider_dispatches set dispatch_status=?,reason_code=?,http_status=?,attempts=?,response_sha256=?,completed_at=now() where id=?",outcome,reason,httpStatus,attempts,responseDigest,dispatchId); try { var project=access.requireProject(projectId); audit.append(project.getOrganizationId(),projectId,subject,"runtime_ai.provider_dispatched","runtime_ai_provider_dispatch",dispatchId.toString(),"{\"profileSha256\":\""+sha256(profile.id().toString()+"|"+profile.endpoint())+"\",\"requestSha256\":\""+requestDigest+"\",\"responseSha256\":\""+(responseDigest==null?"":responseDigest)+"\",\"reason\":\""+reason+"\",\"attempts\":"+attempts+"}"); } catch(RuntimeException evidenceFailure){ return new InvocationResult("FAILED","PROVIDER_EVIDENCE_FAILURE",null,attempts,requestDigest,null,null,runtimeDecisionId); } return new InvocationResult(outcome,reason,httpStatus,attempts,requestDigest,responseDigest,body,runtimeDecisionId); }
  private Profile profile(UUID projectId,String provider,String model){return jdbc.queryForObject("select id,endpoint_uri,credential_reference,require_mtls,mtls_reference,timeout_ms,max_attempts from runtime_ai_provider_profiles where project_id=? and provider_name=? and model_name=? and active=true and endpoint_uri is not null",(rs,n)->new Profile(rs.getObject(1,UUID.class),validatedEndpoint(rs.getString(2)),rs.getString(3),rs.getBoolean(4),rs.getString(5),rs.getInt(6),rs.getInt(7)),projectId,provider,model);}
  private static URI validatedEndpoint(String value){try{URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("Invalid provider endpoint");return uri;}catch(RuntimeException e){throw new IllegalArgumentException("Invalid provider endpoint",e);}}
  private static void validate(InvocationRequest request){if(request==null||request.agentSessionId()==null||request.idempotencyKey()==null||request.payload()==null||request.policyContext()==null||blank(request.provider())||request.provider().length()>160||blank(request.model())||request.model().length()>240||request.requestFingerprint()==null||!request.requestFingerprint().matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Invalid provider invocation");}
  private static boolean retryable(int status){return status==408||status==429||status>=500;}
  private static void pause(int attempt){try{Thread.sleep(50L*attempt);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
  private static boolean blank(String value){return value==null||value.isBlank();}
  private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Digest unavailable",e);}}
}
