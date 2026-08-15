package ai.xdev.aisdlc.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/api/v1/cli/**").hasAnyAuthority("ROLE_admin", "ROLE_developer")
            .requestMatchers("/api/v1/reviews/**").hasAnyAuthority("ROLE_admin", "ROLE_reviewer")
            .requestMatchers("/api/v1/policies/**", "/api/v1/constitutions/**").hasAuthority("ROLE_admin")
            .requestMatchers("/api/**").authenticated()
            .anyRequest().denyAll())
        .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RealmRoleConverter());
    return converter;
  }

  static final class RealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      List<String> explicitRoles = jwt.getClaimAsStringList("roles");
      Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
      List<String> nestedRoles = realmAccess == null ? List.of() :
          ((List<?>) realmAccess.getOrDefault("roles", List.of())).stream().map(String::valueOf).toList();
      return Stream.concat(explicitRoles == null ? Stream.empty() : explicitRoles.stream(), nestedRoles.stream())
          .filter(role -> role.equals("admin") || role.equals("developer") || role.equals("reviewer"))
          .distinct()
          .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
          .map(GrantedAuthority.class::cast)
          .toList();
    }
  }
}

