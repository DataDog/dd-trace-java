package datadog.test.agent.assertions;

import datadog.test.agent.AgentSpan;
import datadog.test.agent.AgentTrace;
import org.opentest4j.AssertionFailedError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public final class AgentTraceMatcher {
  private final Options options;
  private final AgentSpanMatcher[] matchers;

  private AgentTraceMatcher(Options options, AgentSpanMatcher[] matchers) {
    if (matchers.length == 0) {
      throw new IllegalArgumentException("No span matchers provided");
    }
    this.options = options;
    this.matchers = matchers;
  }

  /**
   * Checks a trace structure.
   *
   * @param matchers The matchers to verify the trace structure.
   */
  public static AgentTraceMatcher trace(AgentSpanMatcher... matchers) {
    return new AgentTraceMatcher(new Options(), matchers);
  }

  /**
   * Checks a trace structure.
   *
   * @param options  The {@link AgentTraceAssertions.Options} to configure the checks.
   * @param matchers The matchers to verify the trace structure.
   */
  public static AgentTraceMatcher trace(Function<Options, Options> options, AgentSpanMatcher... matchers) {
    return new AgentTraceMatcher(options.apply(new Options()), matchers);
  }

  void assertTrace(AgentTrace trace, int traceIndex) {
    List<AgentSpan> spans = trace.spans();
    int spanCount = spans.size();
    if (spanCount != this.matchers.length) {
      throw new AssertionFailedError(
          "Invalid number of spans for trace " + traceIndex + " : "+ trace,
          this.matchers.length,
          spanCount);
    }
    if (this.options.sorter != null) {
      spans = new ArrayList<>(spans);
    }
    AgentSpan previousSpan = null;
    for (int i = 0; i < spanCount; i++) {
      AgentSpan span = spans.get(i);
      this.matchers[i].assertSpan(span, previousSpan);
      previousSpan = span;
    }
  }

  public static class Options {
    Comparator<AgentSpan> sorter = null;

    public Options sorter(Comparator<AgentSpan> sorter) {
      this.sorter = sorter;
      return this;
    }
  }
}
