package ai.xdev.aisdlc.service;

import ai.xdev.aisdlc.config.GitHubAppProperties;
import ai.xdev.aisdlc.domain.DomainTypes.ScmPolicyConclusion;
import ai.xdev.aisdlc.domain.ScmRepositoryLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class GitHubAppClient {
  private record InstallationToken(String value, Instant expiresAt) {}
  private final GitHubAppProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Map<Long, InstallationToken> installationTokens = new ConcurrentHashMap<>();

  public GitHubAppClient(GitHubAppProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getRequestTimeout()).build();
  }

  public boolean isAvailable() { return properties.isAppConfigured(); }

  public long createCheckRun(ScmRepositoryLink link, String commitSha, ScmPolicyConclusion conclusion, String summary, String externalId) {
    JsonNode response = sendCheckRun(link, null, commitSha, conclusion, summary, externalId);
    return response.path("id").asLong();
  }

  public void updateCheckRun(ScmRepositoryLink link, long checkRunId, String commitSha, ScmPolicyConclusion conclusion, String summary, String externalId) {
    sendCheckRun(link, checkRunId, commitSha, conclusion, summary, externalId);
  }

  private JsonNode sendCheckRun(ScmRepositoryLink link, Long checkRunId, String commitSha, ScmPolicyConclusion conclusion, String summary, String externalId) {
    if (!isAvailable()) throw new IllegalStateException("GitHub App credentials are not configured");
    if (link.getInstallationId() == null) throw new IllegalArgumentException("A GitHub App installation id is required for policy gate publishing");
    String[] repository = repositoryParts(link.getRepositoryFullName());
    try {
      var body = objectMapper.createObjectNode();
      body.put("name", properties.getCheckName());
      if (checkRunId == null) body.put("head_sha", commitSha);
      body.put("status", "completed");
      body.put("conclusion", githubConclusion(conclusion));
      body.put("external_id", externalId);
      body.put("completed_at", Instant.now().toString());
      if (properties.getDetailsUrlTemplate() != null && !properties.getDetailsUrlTemplate().isBlank()) body.put("details_url", properties.getDetailsUrlTemplate().replace("{externalId}", externalId));
      var output = body.putObject("output");
      output.put("title", properties.getCheckName());
      output.put("summary", summary);
      String path = checkRunId == null
          ? "/repos/" + repository[0] + "/" + repository[1] + "/check-runs"
          : "/repos/" + repository[0] + "/" + repository[1] + "/check-runs/" + checkRunId;
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(normalizeBaseUrl() + path))
          .timeout(properties.getRequestTimeout())
          .header("Accept", "application/vnd.github+json")
          .header("X-GitHub-Api-Version", "2026-03-10")
          .header("Authorization", "Bearer " + installationToken(link.getInstallationId()))
          .header("Content-Type", "application/json");
      HttpResponse<String> response = httpClient.send(checkRunId == null
          ? request.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build()
          : request.method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("GitHub Checks API returned HTTP " + response.statusCode());
      return objectMapper.readTree(response.body());
    } catch (Exception error) {
      throw new IllegalStateException("Unable to publish GitHub policy Check Run", error);
    }
  }

  private String installationToken(long installationId) {
    InstallationToken cached = installationTokens.get(installationId);
    if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) return cached.value();
    synchronized (installationTokens) {
      cached = installationTokens.get(installationId);
      if (cached != null && cached.expiresAt().isAfter(Instant.now().plusSeconds(60))) return cached.value();
      try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(normalizeBaseUrl() + "/app/installations/" + installationId + "/access_tokens"))
            .timeout(properties.getRequestTimeout())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("Authorization", "Bearer " + appJwt())
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 201) throw new IllegalStateException("GitHub installation token endpoint returned HTTP " + response.statusCode());
        JsonNode payload = objectMapper.readTree(response.body());
        String token = payload.path("token").asText();
        Instant expiresAt = Instant.parse(payload.path("expires_at").asText());
        if (token.isBlank()) throw new IllegalStateException("GitHub installation token response did not contain a token");
        InstallationToken refreshed = new InstallationToken(token, expiresAt);
        installationTokens.put(installationId, refreshed);
        return refreshed.value();
      } catch (Exception error) {
        throw new IllegalStateException("Unable to acquire GitHub installation token", error);
      }
    }
  }

  private String appJwt() throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(properties.getAppId()).issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now.plusSeconds(540))).build();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
    jwt.sign(new RSASSASigner((RSAPrivateKey) privateKey()));
    return jwt.serialize();
  }

  private PrivateKey privateKey() throws Exception {
    String normalized = properties.getPrivateKeyPem().replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(normalized)));
  }

  private String normalizeBaseUrl() { return properties.getApiBaseUrl().replaceAll("/+$", ""); }
  private String[] repositoryParts(String fullName) {
    String[] parts = fullName.split("/", -1);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) throw new IllegalArgumentException("GitHub repository must be in owner/name form");
    return parts;
  }
  private String githubConclusion(ScmPolicyConclusion conclusion) {
    return switch (conclusion) {
      case SUCCESS -> "success";
      case FAILURE -> "failure";
      case ACTION_REQUIRED -> "action_required";
      case NEUTRAL -> "neutral";
    };
  }
}
