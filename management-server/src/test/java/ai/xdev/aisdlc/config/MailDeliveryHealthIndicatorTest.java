package ai.xdev.aisdlc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.actuate.endpoint.SimpleStatusAggregator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * An unreachable mail relay used to make {@code /actuator/health} report {@code DOWN} with HTTP 503, which said the
 * control plane was out of service because an optional notification channel had nowhere to send. These tests pin the
 * corrected behaviour: the capability is reported honestly, and it cannot drag the service's own status down.
 */
class MailDeliveryHealthIndicatorTest {
  @SuppressWarnings("unchecked")
  private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
    ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(sender);
    return provider;
  }

  @Test void noConfiguredHostIsDegradedRatherThanDown() {
    JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);

    Health health = new MailDeliveryHealthIndicator(provider(sender), "  ").health();

    assertEquals(MailDeliveryHealthIndicator.DEGRADED, health.getStatus());
    assertNotEquals(Status.DOWN, health.getStatus(), "an absent SMTP host is not a service fault");
    assertEquals("unavailable", health.getDetails().get("emailDelivery"));
    assertTrue(String.valueOf(health.getDetails().get("reason")).contains("AISDLC_SMTP_HOST"),
        "the reason must name the setting an operator has to change: " + health.getDetails());
    assertTrue(String.valueOf(health.getDetails().get("impact")).contains("Slack"),
        "it must say which channels still work, so nobody treats this as total loss of notifications");
    // No host means no reason to open a socket on every health poll.
    verifyNoInteractions(sender);
  }

  @Test void aConfiguredAndReachableRelayIsUp() throws Exception {
    JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);

    Health health = new MailDeliveryHealthIndicator(provider(sender), "smtp.example.com").health();

    assertEquals(Status.UP, health.getStatus());
    assertEquals("available", health.getDetails().get("emailDelivery"));
    assertEquals("smtp.example.com", health.getDetails().get("host"));
    verify(sender).testConnection();
  }

  @Test void anUnreachableRelayIsDegradedAndNamesTheHostWithoutLeakingTheMessage() throws Exception {
    JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
    doThrow(new MailSendException("530 auth required for user admin@example.com with password hunter2"))
        .when(sender).testConnection();

    Health health = new MailDeliveryHealthIndicator(provider(sender), "smtp.example.com").health();

    assertEquals(MailDeliveryHealthIndicator.DEGRADED, health.getStatus());
    assertEquals("smtp.example.com", health.getDetails().get("host"));
    String reason = String.valueOf(health.getDetails().get("reason"));
    assertTrue(reason.contains("MailSendException"), "the failure type must be identifiable: " + reason);
    // A relay's rejection message can echo credentials or an internal banner back at the caller.
    assertTrue(!reason.contains("hunter2") && !reason.contains("admin@example.com"),
        "the relay's message must not be copied into the health body: " + reason);
  }

  @Test void aMissingMailSenderBeanIsDegradedNotAnException() {
    Health health = new MailDeliveryHealthIndicator(provider(null), "smtp.example.com").health();

    assertEquals(MailDeliveryHealthIndicator.DEGRADED, health.getStatus());
    assertTrue(String.valueOf(health.getDetails().get("reason")).contains("no mail sender"), health.getDetails().toString());
  }

  /**
   * The assertion the whole change rests on. With the configured order, a degraded capability alongside healthy
   * components must leave the aggregate {@code UP} — otherwise the endpoint goes back to answering 503 and the fix
   * achieves nothing.
   */
  @Test void aDegradedCapabilityDoesNotDragTheAggregateBelowUp() {
    SimpleStatusAggregator aggregator = new SimpleStatusAggregator(
        List.of(Status.DOWN.getCode(), Status.OUT_OF_SERVICE.getCode(), Status.UP.getCode(),
            MailDeliveryHealthIndicator.DEGRADED.getCode(), Status.UNKNOWN.getCode()));

    Status aggregate = aggregator.getAggregateStatus(
        java.util.Set.of(Status.UP, MailDeliveryHealthIndicator.DEGRADED));

    assertEquals(Status.UP, aggregate, "a degraded optional capability must not report the service as anything else");
  }

  /** And a genuine fault must still win, or the ordering would have made the endpoint useless. */
  @Test void arealDownComponentStillWinsOverDegraded() {
    SimpleStatusAggregator aggregator = new SimpleStatusAggregator(
        List.of(Status.DOWN.getCode(), Status.OUT_OF_SERVICE.getCode(), Status.UP.getCode(),
            MailDeliveryHealthIndicator.DEGRADED.getCode(), Status.UNKNOWN.getCode()));

    Status aggregate = aggregator.getAggregateStatus(
        java.util.Set.of(Status.UP, MailDeliveryHealthIndicator.DEGRADED, Status.DOWN));

    assertEquals(Status.DOWN, aggregate, "a database failure must still be reported as DOWN");
  }
}
