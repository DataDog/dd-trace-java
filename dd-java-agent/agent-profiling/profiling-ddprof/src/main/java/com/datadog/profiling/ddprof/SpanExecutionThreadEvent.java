package com.datadog.profiling.ddprof;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Pure-Java JFR event that records the actual execution thread for each span. Emitted from {@code
 * DatadogProfilingIntegration.onSpanFinished()} using the thread information captured in {@code
 * DDSpan.finishAndAddToTrace()} — on the span's own finishing thread, not from the event loop that
 * calls {@code CoreTracer.write()}.
 *
 * <p>The profiling backend ({@code CausalDagExtractor}) reads this event to override the incorrect
 * {@code EVENT_THREAD} on {@code datadog.SpanNode} events (which are emitted at trace completion
 * from the event loop thread, causing all DAG nodes to appear as event-loop threads).
 */
@Name("datadog.SpanExecutionThread")
@Label("Span Execution Thread")
@Category("Datadog")
@StackTrace(false)
class SpanExecutionThreadEvent extends Event {

  @Label("Span ID")
  long spanId;

  @Label("Execution Thread ID")
  long executionThreadId;

  @Label("Execution Thread Name")
  String executionThreadName;
}
