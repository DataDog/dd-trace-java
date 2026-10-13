package datadog.smoketest.backend;

import static java.util.function.UnaryOperator.identity;

import datadog.smoketest.trace.SmokeTraceAssertions;
import datadog.smoketest.trace.TraceMatcher;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.util.PollingConditions;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** This class is a query facade over the traces a {@link AgentBackend} has received. */
public final class Traces {
  /** Default time to wait for traces to arrive from a separately-launched app before giving up. */
  private static final double DEFAULT_TIMEOUT_SECONDS = 30;

  private final Supplier<List<DecodedTrace>> source;

  Traces(Supplier<List<DecodedTrace>> source) {
    this.source = source;
  }

  /**
   * Returns a snapshot of the traces received so far.
   *
   * @return The traces received so far.
   */
  public List<DecodedTrace> getTraces() {
    return this.source.get();
  }

  /**
   * Waits up to {@value DEFAULT_TIMEOUT_SECONDS}s until <em>at least</em> {@code count} traces have
   * been received.
   *
   * @param count The minimum number of traces to wait for.
   * @throws AssertionError If less than {@code count} traces have been received.
   */
  public void waitForTraceCount(int count) {
    waitForTraceCount(count, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Waits until <em>at least</em> {@code count} traces have been received.
   *
   * @param count The minimum number of traces to wait for.
   * @param timeoutSeconds How long to wait, in seconds.
   * @throws AssertionError If less than {@code count} traces have been received.
   */
  public void waitForTraceCount(int count, double timeoutSeconds) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              int actual = getTraces().size();
              if (actual < count) {
                throw new AssertionError(
                    "Expected at least " + count + " trace(s) but got " + actual);
              }
            });
  }

  /**
   * Wait up to the {@value #DEFAULT_TIMEOUT_SECONDS}s until the received traces satisfy the given
   * trace matchers, one {@link TraceMatcher} per expected trace (matched positionally,
   * count-exact).
   *
   * @param matchers The matchers to verify the received traces, one per expected trace.
   * @throws AssertionError If no traces satisfying the given matchers are found.
   */
  public void waitForTraces(TraceMatcher... matchers) {
    waitForTraces(identity(), matchers);
  }

  /**
   * Wait up to the {@value #DEFAULT_TIMEOUT_SECONDS}s until the received traces satisfy the given
   * trace matchers, one {@link TraceMatcher} per expected tracer, matched according the given
   * options as {@link SmokeTraceAssertions#UNORDERED}, {@link
   * SmokeTraceAssertions#IGNORE_ADDITIONAL_TRACES}, or {@link
   * SmokeTraceAssertions#SORT_BY_START_TIME}).
   *
   * @param options The options to configure the trace-collection matching.
   * @param matchers The matchers to verify the received traces, one per expected trace.
   * @throws AssertionError If no traces satisfying the given matchers are found.
   */
  public void waitForTraces(
      UnaryOperator<SmokeTraceAssertions.Options> options, TraceMatcher... matchers) {
    waitForTraces(DEFAULT_TIMEOUT_SECONDS, options, matchers);
  }

  /**
   * Wait up to {@code timeoutSeconds} until the received traces satisfy the given trace matchers,
   * one {@link TraceMatcher} per expected tracer, matched according the given options as {@link
   * SmokeTraceAssertions#UNORDERED}, {@link SmokeTraceAssertions#IGNORE_ADDITIONAL_TRACES}, or
   * {@link SmokeTraceAssertions#SORT_BY_START_TIME}).
   *
   * @param options The options to configure the trace-collection matching.
   * @param matchers The matchers to verify the received traces, one per expected trace.
   * @throws AssertionError If no traces satisfying the given matchers are found.
   */
  public void waitForTraces(
      double timeoutSeconds,
      UnaryOperator<SmokeTraceAssertions.Options> options,
      TraceMatcher... matchers) {
    new PollingConditions(timeoutSeconds)
        .eventually(() -> SmokeTraceAssertions.assertTraces(getTraces(), options, matchers));
  }
}
