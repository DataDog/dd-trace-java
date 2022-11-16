package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal API for registering callbacks that will execute as soon as there is a global tracer
 * installed, or immediately if the global tracer is already installed.
 *
 * <p>The code is separate from {@link GlobalTracer} to not expose this mechanism to end users. It
 * is also based on the assumption that registering callbacks is a rare thing, and as of this
 * writing the only code doing it is the various MDC log injection instrumentations.
 */
public class WithGlobalTracer {
  private static final Logger log = LoggerFactory.getLogger(WithGlobalTracer.class);

  private WithGlobalTracer() {}

  /**
   * Register a callback to be run when the global tracer is installed, or execute it right now if
   * the tracer is already installed.
   */
  public static void registerOrExecute(final Callback callback) {
    GlobalTracer.registerInstallationCallback(
        new GlobalTracer.Callback() {
          @Override
          public void installed(Tracer tracer) {
            if (tracer instanceof TracerAPI) {
              callback.withTracer((TracerAPI) tracer);
            } else {
              log.warn("Unsupported tracer type {}", tracer.getClass().getName());
            }
          }
        });
  }

  public interface Callback {
    void withTracer(TracerAPI tracer);
  }
}
