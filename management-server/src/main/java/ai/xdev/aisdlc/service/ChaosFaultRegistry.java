package ai.xdev.aisdlc.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test-only fault seam. It is not registered unless the explicit `chaos` profile is active. */
@Component
@Profile("chaos")
public class ChaosFaultRegistry {
  public enum Component { POLICY_ENGINE, NOTIFICATION_PROVIDER, EVIDENCE_STORAGE, AUTHENTICATION, SCM_INGRESS, RUNTIME_AI_PROVIDER }
  public enum Mode { NONE, TIMEOUT, UNAVAILABLE }
  private final Map<Component, Mode> faults = new ConcurrentHashMap<>();
  public void enable(Component component, Mode mode) { if (mode == Mode.NONE) faults.remove(component); else faults.put(component, mode); }
  public void clear() { faults.clear(); }
  public void check(Component component) {
    Mode mode = faults.getOrDefault(component, Mode.NONE);
    if (mode == Mode.TIMEOUT) throw new ChaosFaultException(component, "Injected timeout");
    if (mode == Mode.UNAVAILABLE) throw new ChaosFaultException(component, "Injected unavailability");
  }
  public static final class ChaosFaultException extends RuntimeException { public ChaosFaultException(Component component, String message) { super(component + ": " + message); } }
}
