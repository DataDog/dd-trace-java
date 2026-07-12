package datadog.smoketest.backend;

import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.util.PollingConditions;
import java.util.List;
import java.util.function.Supplier;

/**
 * Query facade over the traces a {@link TraceBackend} has received. It is backend-agnostic: it
 * reads a live snapshot of decoded traces, so the same assertions run against either backend (Q2).
 *
 * <p>S2 provides trace retrieval and count-polling. The fluent matcher integration ({@code
 * assertTraces(trace(span()...))} over the smoke matcher in {@code datadog.smoketest.trace}) and
 * the test-agent trace-invariant checks land in S5.
 */
public final class Traces {
  /** Default time to wait for traces to arrive from a separately-launched app before giving up. */
  private static final double DEFAULT_TIMEOUT_SECONDS = 10;

  private final Supplier<List<DecodedTrace>> source;

  Traces(Supplier<List<DecodedTrace>> source) {
    this.source = source;
  }

  /** A snapshot of the traces received so far. */
  public List<DecodedTrace> getTraces() {
    return source.get();
  }

  /**
   * Waits (up to the default timeout) until <em>at least</em> {@code count} traces have been
   * received, then returns for chaining. Traces arrive asynchronously from the app, so callers wait
   * for the expected count before asserting structure.
   */
  public Traces waitForTraceCount(int count) {
    return waitForTraceCount(count, DEFAULT_TIMEOUT_SECONDS);
  }

  /** As {@link #waitForTraceCount(int)}, but overriding the timeout for this call. */
  public Traces waitForTraceCount(int count, double timeoutSeconds) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              int actual = getTraces().size();
              if (actual < count) {
                throw new AssertionError(
                    "Expected at least " + count + " trace(s) but got " + actual);
              }
            });
    return this;
  }

  // TODO S5: fluent assertTraces(TraceMatcher...) delegating to the smoke matcher, and (test-agent
  //  backends only) trace-invariant check assertions over /test/trace_check/failures.
}
