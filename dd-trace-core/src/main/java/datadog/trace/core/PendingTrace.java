package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.core.util.Clock;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;

/**
 * This class implements the following data flow rules when a Span is finished:
 *
 * <ul>
 *   <li>Immediate Write
 *       <ul>
 *         <li>pending ref count == 0 && trace not already written
 *         <li>not root span && size exceeds partial flush
 *       </ul>
 *   <li>Delayed Write
 *       <ul>
 *         <li>is root span && pending ref count > 0
 *         <li>not root span && pending ref count > 0 && trace already written
 *       </ul>
 * </ul>
 *
 * Delayed write is handled by PendingTraceBuffer. <br>
 */
@Slf4j
public class PendingTrace implements AgentTrace {

  static class Factory {
    private final CoreTracer tracer;

    Factory(CoreTracer tracer) {
      this.tracer = tracer;
    }

    PendingTrace create(final DDId traceId) {
      return new PendingTrace(tracer, traceId);
    }
  }

  private final CoreTracer tracer;
  private final DDId traceId;

  // TODO: consider moving these time fields into DDTracer to ensure that traces have precise
  // relative time
  /** Trace start time in nano seconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nano second ticks value at trace start */
  private final long startNanoTicks;

  /**
   * Updated with the latest nanoTicks each time getCurrentTimeNano is called (at the start and
   * finish of each span).
   */
  private volatile long lastReferenced = 0;

  private PendingTrace(final CoreTracer tracer, final DDId traceId) {
    this.tracer = tracer;
    this.traceId = traceId;

    startTimeNano = Clock.currentNanoTime();
    startNanoTicks = Clock.currentNanoTicks();
  }

  CoreTracer getTracer() {
    return tracer;
  }

  /**
   * Current timestamp in nanoseconds.
   *
   * <p>Note: it is not possible to get 'real' nanosecond time. This method uses trace start time
   * (which has millisecond precision) as a reference and it gets time with nanosecond precision
   * after that. This means time measured within same Trace in different Spans is relatively correct
   * with nanosecond precision.
   *
   * @return timestamp in nanoseconds
   */
  public long getCurrentTimeNano() {
    long nanoTicks = Clock.currentNanoTicks();
    lastReferenced = nanoTicks;
    return startTimeNano + Math.max(0, nanoTicks - startNanoTicks);
  }

  public boolean lastReferencedNanosAgo(long nanos) {
    long currentNanoTicks = Clock.currentNanoTicks();
    long age = currentNanoTicks - lastReferenced;
    return nanos < age;
  }

  public void registerSpan(final DDSpan span) {}

  public void addFinishedSpan(final DDSpan span) {
    write(span);
  }

  public DDSpan getRootSpan() {
    return null;
  }

  /**
   * When using continuations, it's possible one may be used after all existing spans are otherwise
   * completed, so we need to wait till continuations are de-referenced before reporting.
   */
  @Override
  public void registerContinuation(final AgentScope.Continuation continuation) {}

  @Override
  public void cancelContinuation(final AgentScope.Continuation continuation) {}

  private void write(DDSpan span) {
    tracer.write(Collections.singletonList(span));
  }
}
