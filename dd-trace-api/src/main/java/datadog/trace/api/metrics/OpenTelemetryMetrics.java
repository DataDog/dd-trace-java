package datadog.trace.api.metrics;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;
import java.util.concurrent.CompletableFuture;

/**
 * Controls Datadog's OTLP metrics export pipeline; shutdown does not disable the OpenTelemetry
 * meter provider, and metrics recorded afterward are not exported.
 */
public final class OpenTelemetryMetrics {
  private OpenTelemetryMetrics() {}

  /**
   * Exports pending metrics. The result is {@code true} after a successful or empty export and
   * {@code false} if export is unavailable, fails, or shutdown has begun. The future has no
   * deadline; a timed wait bounds only the caller and does not cancel export.
   */
  public static CompletableFuture<Boolean> forceFlush() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).forceFlushOtelMetrics();
    }
    return unavailable();
  }

  /**
   * Performs a final export and stops Datadog's metrics export pipeline. Repeated calls observe the
   * first result; {@code false} means the pipeline was unavailable or export or cleanup failed. The
   * future has no deadline; a timed wait bounds only the caller and does not cancel shutdown.
   */
  public static CompletableFuture<Boolean> shutdown() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).shutdownOtelMetrics();
    }
    return unavailable();
  }

  private static CompletableFuture<Boolean> unavailable() {
    return CompletableFuture.completedFuture(false);
  }
}
