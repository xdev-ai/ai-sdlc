package ai.xdev.aisdlc.portal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PortalSecurityConfig {
  @Bean
  SecurityFilterChain portalSecurity(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/", "/css/**", "/images/**", "/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/app/**").authenticated()
            .anyRequest().authenticated())
        .oauth2Login(login -> login.defaultSuccessUrl("/app", true))
        .logout(logout -> logout.logoutSuccessUrl("/"));
    return http.build();
  }
}

