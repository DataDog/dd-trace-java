package datadog.smoketest.concurrent;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Tests the structured task scope with multiple nested tasks. Here is the expected task/span
 * structure:
 *
 * <pre>
 *   parent
 *   |-- child1
 *   |   |-- great-child1-1
 *   |   \-- great-child1-2
 *   \-- child2
 * </pre>
 */
class NestedTasksTest extends AbstractStructuredConcurrencyTest {
  @Override
  protected String testCaseName() {
    return "NestedTasks";
  }

  @Override
  protected Predicate<DecodedTrace> checkTrace() {
    // 'parent' -> 'child1' -> {'great-child1-1', 'great-child1-2'}, and 'parent' -> 'child2'
    return trace ->
        trace.getSpans().size() == 5
            && findRootSpan(trace, "parent")
                .filter(
                    parent ->
                        findChildSpan(trace, "child1", parent.getSpanId())
                            .filter(
                                child1 ->
                                    hasChildSpan(trace, "child2", parent.getSpanId())
                                        && hasChildSpan(trace, "great-child1-1", child1.getSpanId())
                                        && hasChildSpan(
                                            trace, "great-child1-2", child1.getSpanId()))
                            .isPresent())
                .isPresent();
  }

  @Test
  void testNestedTasks() throws Exception {
    receivedCorrectTrace();
  }
}
