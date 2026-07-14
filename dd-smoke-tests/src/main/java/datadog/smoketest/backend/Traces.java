package datadog.smoketest.backend;

import datadog.smoketest.trace.SmokeTraceAssertions;
import datadog.smoketest.trace.SpanMatcher;
import datadog.smoketest.trace.TraceMatcher;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.util.PollingConditions;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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

  /**
   * Asserts the received traces against the thin smoke matcher — one {@link TraceMatcher} per
   * expected trace (matched positionally). Sugar over {@link SmokeTraceAssertions#assertTraces}:
   *
   * <pre>{@code
   * app.traces().waitForTraceCount(1)
   *    .assertTraces(trace(span().operationName("servlet.request").resourceName("GET /greeting")));
   * }</pre>
   */
  public void assertTraces(TraceMatcher... matchers) {
    int expectedTraceCount = matchers.length;
    waitForTraceCount(expectedTraceCount);
    SmokeTraceAssertions.assertTraces(getTraces(), matchers);
  }

  /**
   * As {@link #assertTraces(TraceMatcher...)} with options (e.g. {@code
   * SmokeTraceAssertions.IGNORE_ADDITIONAL_TRACES} / {@code SORT_BY_ROOT_SPAN_ID}).
   */
  public void assertTraces(
      UnaryOperator<SmokeTraceAssertions.Options> options, TraceMatcher... matchers) {
    SmokeTraceAssertions.assertTraces(getTraces(), options, matchers);
  }

  /**
   * Polls (up to the default timeout) until some received trace contains a parent-child chain of
   * spans matching {@code chain} (see {@link SmokeTraceAssertions#assertContainsChain}). Combines
   * waiting for async arrival with the structural assertion, so it suits distributed traces whose
   * full span set arrives piecemeal — assert the linkage you care about, ignore the rest:
   *
   * <pre>{@code
   * app.traces().assertContainsChain(
   *     span().service("web").operationName("servlet.request").root(),
   *     span().service("web").operationName("amqp.command").resourceName(startsWith("basic.publish")));
   * }</pre>
   */
  public Traces assertContainsChain(SpanMatcher... chain) {
    return assertContainsChain(DEFAULT_TIMEOUT_SECONDS, chain);
  }

  /** As {@link #assertContainsChain(SpanMatcher...)}, but overriding the timeout for this call. */
  public Traces assertContainsChain(double timeoutSeconds, SpanMatcher... chain) {
    new PollingConditions(timeoutSeconds)
        .eventually(() -> SmokeTraceAssertions.assertContainsChain(getTraces(), chain));
    return this;
  }

  /**
   * Polls (up to {@code timeoutSeconds}) until at least {@code minMatches} received traces each
   * contain a chain matching {@code chain} (see {@link SmokeTraceAssertions#countChainMatches}).
   * Use to verify that N independent operations each produced the expected trace — e.g. one
   * distributed trace per request — not merely that one did.
   */
  public Traces assertContainsChain(int minMatches, double timeoutSeconds, SpanMatcher... chain) {
    new PollingConditions(timeoutSeconds)
        .eventually(
            () -> {
              long found = SmokeTraceAssertions.countChainMatches(getTraces(), chain);
              if (found < minMatches) {
                throw new AssertionError(
                    "Expected at least "
                        + minMatches
                        + " traces containing the chain but found "
                        + found);
              }
            });
    return this;
  }

  // Trace-invariant checks (ENABLED_CHECKS) are a test-agent-specific concern, validated by that
  // backend itself (TestAgentBackend#assertNoInvariantFailures, auto-run at container teardown per
  // Q5) rather than on this common facade, which stays portable across both backends (Q2).
}
