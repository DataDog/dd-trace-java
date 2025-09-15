package datadog.smoketest.concurrent

import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.function.Function

/**
 * Tests the structured task scope with a multiple nested tasks.
 * Here is the expected task/span structure:
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
    'NestedTasks'
  }

  @Override
  protected Function<DecodedTrace, Boolean> checkTrace() {
    return {
      trace ->
      // Look for 'parent' span
      def parentSpan = findRootSpan(trace, 'parent')
      // Look for 'child1' span
      def child1Span = parentSpan ? findChildSpan(trace, 'child1', parentSpan.spanId) : null
      // Look for 'child2', 'great-child1-1' and 'great-child1-2' spans
      return child1Span && findChildSpan(trace, 'child2', parentSpan.spanId)
      && findChildSpan(trace, 'great-child1-1', child1Span.spanId)
      && findChildSpan(trace, 'great-child1-2', child1Span.spanId)
    }
  }

  def 'test nested tasks'() {
    expect:
    receivedCorrectTrace()
  }
}
