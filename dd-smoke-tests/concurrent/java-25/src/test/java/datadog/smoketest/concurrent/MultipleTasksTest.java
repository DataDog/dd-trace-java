package datadog.smoketest.concurrent;

import datadog.trace.test.agent.decoder.DecodedTrace;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Tests the structured task scope with multiple tasks. Here is the expected task/span structure:
 *
 * <pre>
 *   parent
 *   |-- child1
 *   |-- child2
 *   \-- child3
 * </pre>
 */
class MultipleTasksTest extends AbstractStructuredConcurrencyTest {
  @Override
  protected String testCaseName() {
    return "MultipleTasks";
  }

  @Override
  protected Predicate<DecodedTrace> checkTrace() {
    // 'parent' with 'child1', 'child2', and 'child3' as direct children
    return trace ->
        trace.getSpans().size() == 4
            && findRootSpan(trace, "parent")
                .filter(
                    parent ->
                        hasChildSpan(trace, "child1", parent.getSpanId())
                            && hasChildSpan(trace, "child2", parent.getSpanId())
                            && hasChildSpan(trace, "child3", parent.getSpanId()))
                .isPresent();
  }

  @Test
  void testMultipleTasks() throws Exception {
    receivedCorrectTrace();
  }
}
