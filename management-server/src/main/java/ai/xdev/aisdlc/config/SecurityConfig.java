package ai.xdev.aisdlc.config;

import ai.xdev.aisdlc.service.ChaosFaultRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
  /** Realm roles that identify a human control-plane caller. */
  public static final Set<String> HUMAN_ROLES = Set.of("admin", "developer", "reviewer", "viewer");
  /** Realm role of a non-human agent workload; it never grants a human authority. */
  public static final String AGENT_RUNTIME_ROLE = "agent_runtime";
  /** Internal surface reachable only by an authenticated agent workload. */
  public static final String RUNTIME_PATH_PREFIX = "/internal/runtime-ai";

  private final List<String> allowedOrigins;

  public SecurityConfig(@Value("${aisdlc.security.allowed-origins:http://localhost:8080}") List<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"))
            .frameOptions(frame -> frame.deny())
            .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .permissionsPolicyHeader(policy -> policy.policy("geolocation=(), camera=(), microphone=()"))
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31536000)))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers("/api/v1/webhooks/github", "/scim/v2/**").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasAuthority("ROLE_admin")
            .requestMatchers("/api/v1/cli/**").hasAnyAuthority("ROLE_admin", "ROLE_developer")
            .requestMatchers("/api/v1/reviews/**").hasAnyAuthority("ROLE_admin", "ROLE_reviewer")
            .requestMatchers("/api/v1/policies/**", "/api/v1/constitutions/**").hasAuthority("ROLE_admin")
            .requestMatchers(RUNTIME_PATH_PREFIX + "/**").hasAuthority("ROLE_" + AGENT_RUNTIME_ROLE)
            // A workload token carries only ROLE_agent_runtime, so naming the human roles keeps agents off the
            // human control plane instead of admitting every authenticated principal.
            .requestMatchers("/api/**").hasAnyAuthority("ROLE_admin", "ROLE_developer", "ROLE_reviewer", "ROLE_viewer")
            .anyRequest().denyAll())
        .oauth2ResourceServer(oauth -> oauth.bearerTokenResolver(scimAwareBearerTokenResolver()).jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins);
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
    configuration.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
    configuration.setAllowCredentials(false);
    configuration.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", configuration);
    return source;
  }

  /** Replaces the auto-configured decoder so audience and authorized-party checks run before authorization. */
  @Bean
  JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
                        RuntimeAudienceProperties audiences,
                        ObjectProvider<ChaosFaultRegistry> chaosFaults) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuerUri), new RuntimeTokenValidator(audiences)));
    return chaosAwareDecoder(decoder, chaosFaults);
  }

  /**
   * Losing the identity dependency rejects a new authorization; it never falls back to a cached or alternative
   * principal. The seam is inert unless the isolated {@code chaos} profile registered the registry.
   */
  public static JwtDecoder chaosAwareDecoder(JwtDecoder delegate, ObjectProvider<ChaosFaultRegistry> chaosFaults) {
    if (chaosFaults == null) return delegate;
    return token -> {
      try {
        chaosFaults.ifAvailable(registry -> registry.check(ChaosFaultRegistry.Component.AUTHENTICATION));
      } catch (ChaosFaultRegistry.ChaosFaultException injected) {
        throw new JwtException("The identity dependency is unavailable");
      }
      return delegate.decode(token);
    };
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RealmRoleConverter());
    return converter;
  }

  @Bean
  BearerTokenResolver scimAwareBearerTokenResolver() {
    DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
    return (HttpServletRequest request) -> request.getRequestURI().startsWith("/scim/v2/") ? null : delegate.resolve(request);
  }

  static final class RealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      List<String> roles = realmRoles(jwt);
      boolean runtimeWorkload = roles.contains(AGENT_RUNTIME_ROLE);
      boolean humanRole = roles.stream().anyMatch(HUMAN_ROLES::contains);
      // A token claiming both identities is an impersonation attempt, not a superset of privileges.
      if (runtimeWorkload && humanRole) return List.of();
      if (runtimeWorkload) return List.of(new SimpleGrantedAuthority("ROLE_" + AGENT_RUNTIME_ROLE));
      return roles.stream()
          .filter(HUMAN_ROLES::contains)
          .distinct()
          .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .map(GrantedAuthority.class::cast)
          .toList();
    }

    /** Reads the supported realm roles from either the flat {@code roles} claim or Keycloak's {@code realm_access}. */
    static List<String> realmRoles(Jwt jwt) {
      List<String> explicitRoles = jwt.getClaimAsStringList("roles");
      Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
      List<String> nestedRoles = realmAccess == null ? List.of() :
          ((List<?>) realmAccess.getOrDefault("roles", List.of())).stream().map(String::valueOf).toList();
      return Stream.concat(explicitRoles == null ? Stream.empty() : explicitRoles.stream(), nestedRoles.stream())
          .filter(role -> HUMAN_ROLES.contains(role) || AGENT_RUNTIME_ROLE.equals(role))
          .distinct()
          .toList();
    }
  }
}
