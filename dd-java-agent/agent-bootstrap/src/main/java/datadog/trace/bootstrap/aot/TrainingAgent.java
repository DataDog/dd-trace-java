package datadog.trace.bootstrap.aot;

import java.lang.instrument.Instrumentation;
import java.net.URL;

/** Prepares the agent for Ahead-of-Time training. */
public final class TrainingAgent {
  public static void start(
      final Object bootstrapInitTelemetry,
      final Instrumentation inst,
      final URL agentJarURL,
      final String agentArgs) {

    // apply TraceInterceptor LinkageError workaround
    inst.addTransformer(new TraceApiTransformer());

    // don't start services, they won't be cached as they use a custom classloader
  }
}
