package datadog.trace.api.metrics;

import datadog.trace.api.GlobalTracer;
import datadog.trace.api.Tracer;
import datadog.trace.api.internal.InternalTracer;
import java.util.concurrent.CompletableFuture;

public final class OpenTelemetryMetrics {
  private OpenTelemetryMetrics() {}

  public static CompletableFuture<Boolean> forceFlush() {
    Tracer tracer = GlobalTracer.get();
    if (tracer instanceof InternalTracer) {
      return ((InternalTracer) tracer).forceFlushOtelMetrics();
    }
    return unavailable();
  }

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
