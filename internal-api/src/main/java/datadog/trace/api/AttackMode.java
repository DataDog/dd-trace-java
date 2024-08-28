package datadog.trace.api;

import java.util.concurrent.atomic.AtomicBoolean;

public class AttackMode {
  private static final AtomicBoolean CURRENT_MODE = new AtomicBoolean();

  public static void setEnabled(boolean enabled) {
    CURRENT_MODE.set(enabled);
  }

  public static boolean isEnabled() {
    return CURRENT_MODE.get();
  }
}
