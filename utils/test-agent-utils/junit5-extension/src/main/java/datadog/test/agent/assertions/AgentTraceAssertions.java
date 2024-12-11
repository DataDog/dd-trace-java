package datadog.test.agent.assertions;

import datadog.test.agent.AgentTrace;
import datadog.test.agent.TestAgentClient;
import org.opentest4j.AssertionFailedError;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class AgentTraceAssertions {
  public static final Function<Options, Options> IGNORE_ADDITIONAL_TRACES = Options::ignoredAdditionalTraces;

  private AgentTraceAssertions() {
  }

  /**
   * Checks a trace structure.
   *
   * @param trace   The trace to check.
   * @param matcher The matcher to verify the trace structure.
   */
  public static void assertTrace(AgentTrace trace, AgentTraceMatcher matcher) {
    matcher.assertTrace(trace, 0);
  }

  /**
   * Checks the structure of the traces captured from the test agent.
   *
   * @param client   The test agent client to get traces from.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(TestAgentClient client, AgentTraceMatcher... matchers) {
    assertTraces(client, Function.identity(), matchers);
  }

  /**
   * Checks the structure of the traces captured from the test agent.
   *
   * @param client   The agent client to get traces from.
   * @param options  The {@link Options} to configure the checks.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(TestAgentClient client, Function<Options, Options> options, AgentTraceMatcher... matchers) {
    int expectedTraceCount = matchers.length;
    List<AgentTrace> traces;
    try {
      traces = client.waitForTraces(expectedTraceCount);
    } catch (TimeoutException e) {
      throw new AssertionFailedError("Timeout while waiting for traces", e);
    }
    assertTraces(traces, options, matchers);
  }

  /**
   * Checks the structure of a trace collection.
   *
   * @param traces   The trace collection to check.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(List<AgentTrace> traces, AgentTraceMatcher... matchers) {
    assertTraces(traces, Function.identity(), matchers);
  }

  /**
   * Checks the structure of a trace collection.
   *
   * @param traces   The trace collection to check.
   * @param options  The {@link Options} to configure the checks.
   * @param matchers The matchers to verify the trace collection, one matcher by expected trace.
   */
  public static void assertTraces(List<AgentTrace> traces, Function<Options, Options> options, AgentTraceMatcher... matchers) {
    Options opts = options.apply(new Options());
    int expectedTraceCount = matchers.length;
    int traceCount = traces.size();
    if (opts.ignoredAdditionalTraces) {
      if (traceCount < expectedTraceCount) {
        throw new AssertionFailedError("Not enough of traces", expectedTraceCount, traceCount);
      }
    } else {
      if (traceCount != expectedTraceCount) {
        throw new AssertionFailedError("Invalid number of traces", expectedTraceCount, traceCount);
      }
    }
    if (opts.sorter != null) {
      traces.sort(opts.sorter);
    }
    for (int i = 0; i < expectedTraceCount; i++) {
      AgentTrace trace = traces.get(i);
      matchers[i].assertTrace(trace, i);
    }
  }

  public static class Options {
    boolean ignoredAdditionalTraces = false;
    Comparator<AgentTrace> sorter = null;

    public Options ignoredAdditionalTraces() {
      ignoredAdditionalTraces = true;
      return this;
    }

    public Options sorter(Comparator<AgentTrace> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}
