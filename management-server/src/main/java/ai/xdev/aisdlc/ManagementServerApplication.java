package ai.xdev.aisdlc;

import ai.xdev.aisdlc.repo.Repositories;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackageClasses = Repositories.class, considerNestedRepositories = true)
public class ManagementServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(ManagementServerApplication.class, args);
  }
}
