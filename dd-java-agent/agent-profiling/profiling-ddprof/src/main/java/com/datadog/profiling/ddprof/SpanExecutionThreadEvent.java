package com.datadog.profiling.ddprof;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Pure-Java JFR event that records the actual execution thread for each span.
 *
 * <p>The {@code executionThreadId} and {@code executionThreadName} fields are captured on the
 * span's own finishing thread in {@code DDSpan.finishAndAddToTrace()} / {@code phasedFinish()},
 * using first-write-wins semantics so that an earlier {@code onTaskActivation()} call on a worker
 * thread takes precedence over a later event-loop callback. The JFR event itself is committed
 * later, from inside {@code CoreTracer.write()} (called on the {@code dd-trace-monitor} drain
 * thread or the span-finishing thread), so the JFR {@code EVENT_THREAD} is unrelated to the span's
 * actual execution thread.
 *
 * <p>The profiling backend ({@code CausalDagExtractor}) reads the {@code executionThreadId} /
 * {@code executionThreadName} fields — not the JFR {@code EVENT_THREAD} — to override the incorrect
 * thread attribution on {@code datadog.SpanNode} events, which are always emitted from the
 * event-loop thread that calls {@code CoreTracer.write()}.
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
