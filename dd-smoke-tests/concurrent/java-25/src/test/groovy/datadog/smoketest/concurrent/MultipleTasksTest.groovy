package datadog.smoketest.concurrent

import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.function.Function

/**
 * Tests the structured task scope with a multiple tasks.
 * Here is the expected task/span structure:
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
    'MultipleTasks'
  }

  @Override
  protected Function<DecodedTrace, Boolean> checkTrace() {
    return {
      trace ->
      // Look for 'parent' span
      def parentSpan = findRootSpan(trace, 'parent')
      // Look for 'child1', 'child2', and 'child3' spans
      return parentSpan && findChildSpan(trace, 'child1', parentSpan.spanId)
      && findChildSpan(trace, 'child2', parentSpan.spanId)
      && findChildSpan(trace, 'child3', parentSpan.spanId)
    }
  }

  def 'test multiple tasks'() {
    expect:
    receivedCorrectTrace()
  }
}
