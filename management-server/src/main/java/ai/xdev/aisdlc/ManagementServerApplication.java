package ai.xdev.aisdlc;

import ai.xdev.aisdlc.config.GitHubAppProperties;
import ai.xdev.aisdlc.config.NotificationProperties;
import ai.xdev.aisdlc.config.RuntimeAudienceProperties;
import ai.xdev.aisdlc.config.ScmConnectorProperties;
import ai.xdev.aisdlc.repo.Repositories;
import ai.xdev.aisdlc.telemetry.TelemetryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({GitHubAppProperties.class, NotificationProperties.class, RuntimeAudienceProperties.class, ScmConnectorProperties.class, TelemetryProperties.class})
@EnableJpaRepositories(basePackageClasses = Repositories.class, considerNestedRepositories = true)
@EnableScheduling
public class ManagementServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(ManagementServerApplication.class, args);
  }
}
