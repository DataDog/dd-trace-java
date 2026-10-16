package jvmbootstraptest;

import java.util.Collections;
import java.util.Map;

/** Activates the agent's production gateway and leaves both event types queued at JVM exit. */
public class FeatureFlaggingShutdownProbe {
  public static void main(String[] args) throws Exception {
    final Class<?> gateway = type("FeatureFlaggingGateway");
    // Agent installation can finish asynchronously on Java 8. This probe tests shutdown after
    // activation, not provider initialization timing.
    final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    Object writer;
    do {
      gateway.getMethod("activate").invoke(null);
      writer = gateway.getMethod("getFlagEvalWriter").invoke(null);
      if (writer != null) {
        break;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    if (writer == null) {
      throw new AssertionError("Provider activation did not start the evaluation writer");
    }
    final Class<?> exposure = type("exposure.ExposureEvent");
    final Class<?> allocation = type("exposure.Allocation");
    final Class<?> flag = type("exposure.Flag");
    final Class<?> variant = type("exposure.Variant");
    final Class<?> subject = type("exposure.Subject");
    final Object event =
        exposure
            .getConstructor(long.class, allocation, flag, variant, subject)
            .newInstance(
                System.currentTimeMillis(),
                allocation.getConstructor(String.class).newInstance("allocation"),
                flag.getConstructor(String.class).newInstance("shutdown-flag"),
                variant.getConstructor(String.class).newInstance("on"),
                subject
                    .getConstructor(String.class, Map.class)
                    .newInstance("shutdown-user", Collections.emptyMap()));
    gateway.getMethod("dispatch", exposure).invoke(null, event);

    final Class<?> evaluation = type("flagevaluation.FlagEvalEvent");
    final Object evalEvent =
        evaluation
            .getConstructor(
                String.class, String.class, String.class, String.class, long.class, Map.class)
            .newInstance(
                "shutdown-flag",
                "on",
                "allocation",
                "shutdown-user",
                System.currentTimeMillis(),
                Collections.emptyMap());
    type("flagevaluation.FlagEvaluationWriter")
        .getMethod("enqueue", evaluation)
        .invoke(writer, evalEvent);
    System.out.println("EXIT_WITH_QUEUED_FEATURE_FLAG_EVENTS");
  }

  private static Class<?> type(String suffix) throws ClassNotFoundException {
    return Class.forName("datadog.trace.api.featureflag." + suffix, true, null);
  }
}
