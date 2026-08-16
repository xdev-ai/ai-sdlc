package ai.xdev.aisdlc.portal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PortalSecurityConfig {
  @Bean
  SecurityFilterChain portalSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/", "/session-expired", "/oauth2/authorization/**", "/login/**", "/css/**", "/js/**", "/vendor/**", "/react/**", "/images/**", "/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/app/**").authenticated()
            .anyRequest().authenticated())
        .headers(headers -> headers
            .contentSecurityPolicy(policy -> policy.policyDirectives("default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'self'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'"))
            .frameOptions(frame -> frame.sameOrigin())
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/session-expired")))
        .oauth2Login(login -> login.defaultSuccessUrl("/app").failureUrl("/session-expired"))
        .logout(logout -> logout.logoutSuccessUrl("/"));
    return http.build();
  }
}
