package datadog.smoketest.concurrent;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ScheduledForkJoinTaskTest extends AbstractStructuredConcurrencyTest {
  @Override
  protected String testCaseName() {
    return "ScheduledForkJoinTask";
  }

  @Override
  protected Predicate<DecodedTrace> checkTrace() {
    return trace ->
        trace.getSpans().size() == 3
            && findRootSpan(trace, "parent")
                .flatMap(parent -> findChildSpan(trace, "scheduled", parent.getSpanId()))
                .filter(scheduled -> hasChildSpan(trace, "nested", scheduled.getSpanId()))
                .isPresent();
  }

  @Test
  void propagatesDelayedContextAcrossExecutorsAndClearsItAfterExecution() {
    app.assertCompletesWithValue(10, SECONDS, 0);
    app.traces().waitForTraceCount(3);
    List<DecodedTrace> traces = app.traces().getTraces();
    assertEquals(3, traces.size());
    assertTrue(traces.stream().anyMatch(checkTrace()), "Missing scheduled task trace");
    assertTrue(
        traces.stream()
            .anyMatch(trace ->
                trace.getSpans().size() == 1
                    && findRootSpan(trace, "unrelated-forkjoin").isPresent()),
        "Missing unrelated fork-join trace");
    assertTrue(
        traces.stream()
            .anyMatch(trace ->
                trace.getSpans().size() == 1
                    && findRootSpan(trace, "unrelated-executor").isPresent()),
        "Missing unrelated executor trace");
  }
}
