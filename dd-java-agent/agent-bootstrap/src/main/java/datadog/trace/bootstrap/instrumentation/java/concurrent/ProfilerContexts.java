package datadog.trace.bootstrap.instrumentation.java.concurrent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;

/** Internal helpers shared by Java-level blocking instrumentation. */
final class ProfilerContexts {
  private ProfilerContexts() {}

  /**
   * Returns the {@link ProfilerContext} for the given span, or {@code null} if the span is absent
   * or its context does not implement {@code ProfilerContext}. Profiling-aware instrumentation
   * always takes this path before reading span/root-span IDs, so centralising the {@code
   * instanceof} check keeps the semantics identical across helpers.
   */
  static ProfilerContext of(AgentSpan span) {
    if (span == null) {
      return null;
    }
    AgentSpanContext context = span.context();
    return context instanceof ProfilerContext ? (ProfilerContext) context : null;
  }
}
