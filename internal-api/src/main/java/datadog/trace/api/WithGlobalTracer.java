package datadog.trace.api;

import static datadog.trace.api.GlobalTracer.isTracerInstalled;

import java.util.LinkedList;

/**
 * Internal API for registering callbacks that will execute as soon as there is a global tracer
 * installed, or immediately if the global tracer is already installed.
 *
 * <p>The code is separate from {@link GlobalTracer} to not expose this mechanism to end users. It
 * is also based on the assumption that registering callbacks is a rare thing, and as of this
 * writing the only code doing it is the various MDC log injection instrumentations.
 */
public class WithGlobalTracer {
  // Use a plain lock here to guard a normal LinkedList, since there will be very few callbacks
  // registered before there is a global tracer installed, and iteration only happens once.
  private static final Object lock = new Object();
  private static LinkedList<Callback> registeredCallbacks = null;

  /**
   * Register a callback to be run when the global tracer is installed, or execute it right now if
   * the tracer is already installed.
   */
  public static void registerOrExecute(Callback callback) {
    boolean shouldExecute = true;
    if (!isTracerInstalled()) {
      synchronized (lock) {
        if (!isTracerInstalled()) {
          // Should we try to install the global callback?
          if (!GlobalTracer.isCallbackInstalled()) {
            shouldExecute = !GlobalTracer.registerInstallationCallback(GLOBAL_CALLBACK);
          } else {
            shouldExecute = false;
          }
          if (!shouldExecute) {
            if (registeredCallbacks == null) {
              registeredCallbacks = new LinkedList<>();
            }
            registeredCallbacks.addLast(callback);
          }
        }
      }
    }
    if (shouldExecute) {
      callback.withTracer(GlobalTracer.get());
    }
  }

  private static final GlobalTracer.Callback GLOBAL_CALLBACK =
      new GlobalTracer.Callback() {
        @Override
        public void installed(Tracer tracer) {
          LinkedList<Callback> callbacks;
          synchronized (lock) {
            callbacks = registeredCallbacks;
            registeredCallbacks = null;
          }
          if (callbacks != null) {
            for (Callback callback : callbacks) {
              callback.withTracer(tracer);
            }
          }
        }
      };

  public interface Callback {
    void withTracer(Tracer tracer);
  }
}
