package datadog.trace.bootstrap;

import java.util.ArrayList;
import java.util.List;

/** Runtime activation state of the subsystems that can be switched on and off after start-up. */
public class ActiveSubsystems {
  /**
   * Whether AppSec is currently active. Unlike {@code Config.getAppSecActivation()}, which is fixed
   * at boot, this flag follows the remote-config "one-click" activation flow, so it can flip at any
   * time while the application runs.
   *
   * <p>Kept public and directly writable because it is read from hot paths and from tests that set
   * it up by hand. Production code that owns the transition must go through {@link
   * #setAppSecActive(boolean)} instead, so the activation callbacks below are honoured.
   */
  public static volatile boolean APPSEC_ACTIVE;

  /**
   * One-shot callbacks waiting for AppSec to become active, registered through {@link
   * #whenAppSecActivated(Runnable)}. Guarded by its own monitor, which also guards the read of
   * {@link #APPSEC_ACTIVE} on the registration side, so a callback registered concurrently with an
   * activation is neither run twice nor dropped.
   */
  private static final List<Runnable> APPSEC_ACTIVATION_CALLBACKS = new ArrayList<>(1);

  /**
   * Updates {@link #APPSEC_ACTIVE} and, on a transition into the active state, runs the pending
   * activation callbacks.
   *
   * <p>Callbacks fire on the caller's thread, which for the remote-config flow is the configuration
   * poller thread, so they must not block. They fire at most once for the lifetime of the process:
   * AppSec can be activated and deactivated repeatedly through remote config, but the consumers
   * here are one-time initializations.
   */
  public static void setAppSecActive(final boolean active) {
    APPSEC_ACTIVE = active;
    if (!active) {
      return;
    }
    final List<Runnable> callbacks;
    synchronized (APPSEC_ACTIVATION_CALLBACKS) {
      if (APPSEC_ACTIVATION_CALLBACKS.isEmpty()) {
        return;
      }
      callbacks = new ArrayList<>(APPSEC_ACTIVATION_CALLBACKS);
      APPSEC_ACTIVATION_CALLBACKS.clear();
    }
    for (final Runnable callback : callbacks) {
      try {
        callback.run();
      } catch (final Throwable ignored) {
        // A failing callback must never prevent AppSec from becoming active, nor stop the
        // remaining callbacks. There is deliberately no logger here: this class is loaded very
        // early and from hot paths, and each callback is expected to report its own failures.
      }
    }
  }

  /**
   * Runs {@code callback} once AppSec becomes active, or immediately if it already is. The callback
   * is run at most once, and is never run if AppSec never becomes active.
   */
  public static void whenAppSecActivated(final Runnable callback) {
    synchronized (APPSEC_ACTIVATION_CALLBACKS) {
      if (!APPSEC_ACTIVE) {
        APPSEC_ACTIVATION_CALLBACKS.add(callback);
        return;
      }
    }
    callback.run();
  }
}
