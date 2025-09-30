package datadog.smoketest.concurrent

import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.function.Function

abstract class SimpleTest extends AbstractStructuredConcurrencyTest {
  @Override
  protected Function<DecodedTrace, Boolean> checkTrace() {
    return { trace ->
      // Look for 'parent' span
      def parentSpan = findRootSpan(trace, 'parent')
      // Look for 'child' span
      return parentSpan && findChildSpan(trace, 'child', parentSpan.spanId)
    }
  }
}

class SimpleRunnableTest extends SimpleTest {
  @Override
  protected String testCaseName() {
    'SimpleRunnableTask'
  }

  def 'test simple runnable'() {
    expect:
    receivedCorrectTrace()
  }
}

class SimpleCallableTest extends SimpleTest {
  @Override
  protected String testCaseName() {
    'SimpleCallableTask'
  }

  def 'test simple callable'() {
    expect:
    receivedCorrectTrace()
  }
}

