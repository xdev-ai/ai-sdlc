package ai.xdev.aisdlc.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Reports whether email notifications can be delivered — without claiming the control plane is down when they cannot.
 *
 * <p>Spring Boot's own {@code MailHealthIndicator} is binary: it tests the SMTP connection and reports {@code DOWN}
 * when that fails. Because it contributes to the aggregate, {@code /actuator/health} returned {@code DOWN} with HTTP
 * 503 on every environment without an SMTP relay — which is a false statement about this service. The governance API,
 * the audit ledger, evidence storage and every other capability are unaffected by an unreachable mail relay, and
 * email is one notification channel among several: Slack and webhook delivery do not touch SMTP.
 *
 * <p>The hazard was not only cosmetic. An operator reading {@code DOWN} concludes there is an outage, and any probe
 * or load balancer ever pointed at the aggregate would have taken the whole control plane out of service because an
 * optional dependency was unreachable. A non-critical dependency must not be able to cascade like that.
 *
 * <p>So this indicator reports a {@link #DEGRADED} status instead of {@code DOWN}. {@code DEGRADED} is ordered below
 * {@code UP} in {@code management.endpoint.health.status.order}, so the aggregate stays {@code UP} while the component
 * shows exactly what is wrong and why. Nothing is hidden: the statement changes from "the platform is down" to "the
 * platform is up and email delivery is not available", which is the truth in both cases.
 *
 * <p>Readiness deliberately does not include this indicator. A pod that cannot send email is still able to serve
 * every governed request, so removing it from the load balancer would make an unrelated problem worse.
 */
@Component("mailDelivery")
public class MailDeliveryHealthIndicator implements HealthIndicator {
  /**
   * Not {@code DOWN} and not {@code OUT_OF_SERVICE}: both of those are claims about this service's ability to do its
   * job. This says a named capability is unavailable while the service itself is fine.
   */
  public static final Status DEGRADED = new Status("DEGRADED", "A non-critical capability is unavailable.");

  private final ObjectProvider<JavaMailSender> mailSender;
  private final String configuredHost;

  public MailDeliveryHealthIndicator(ObjectProvider<JavaMailSender> mailSender,
      @Value("${spring.mail.host:}") String configuredHost) {
    this.mailSender = mailSender;
    this.configuredHost = configuredHost == null ? "" : configuredHost.strip();
  }

  @Override
  public Health health() {
    if (configuredHost.isEmpty()) {
      // The ordinary state of a development or CI environment. Not a fault, and not worth a connection attempt.
      return Health.status(DEGRADED)
          .withDetail("emailDelivery", "unavailable")
          .withDetail("reason", "no SMTP host is configured (AISDLC_SMTP_HOST is empty)")
          .withDetail("impact", "email notification channels cannot deliver; Slack and webhook channels are unaffected")
          .build();
    }

    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null) {
      return Health.status(DEGRADED)
          .withDetail("emailDelivery", "unavailable")
          .withDetail("reason", "an SMTP host is configured but no mail sender was created")
          .build();
    }

    try {
      if (sender instanceof JavaMailSenderImpl impl) {
        impl.testConnection();
      }
      return Health.up().withDetail("emailDelivery", "available").withDetail("host", configuredHost).build();
    } catch (Exception unreachable) {
      // The relay is named in the configuration but cannot be reached. Still a degraded capability rather than a dead
      // service, and the message is carried so an operator does not have to guess which dependency failed. The class
      // name is deliberate: the exception message can carry the relay's banner or credentials.
      return Health.status(DEGRADED)
          .withDetail("emailDelivery", "unavailable")
          .withDetail("host", configuredHost)
          .withDetail("reason", "the SMTP relay could not be reached: " + unreachable.getClass().getSimpleName())
          .build();
    }
  }
}
