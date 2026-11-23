package datadog.smoketest.concurrent

import datadog.trace.test.agent.decoder.DecodedTrace

import java.util.function.Function

import static java.util.concurrent.TimeUnit.SECONDS

class ScheduledForkJoinTaskTest extends AbstractStructuredConcurrencyTest {
  @Override
  protected String testCaseName() {
    'ScheduledForkJoinTask'
  }

  @Override
  protected Function<DecodedTrace, Boolean> checkTrace() {
    return { trace ->
      def parent = findRootSpan(trace, 'parent')
      def scheduled = parent ? findChildSpan(trace, 'scheduled', parent.spanId) : null
      return trace.spans.size() == 3 && scheduled && findChildSpan(trace, 'nested', scheduled.spanId)
    }
  }

  def 'propagates delayed context across executors and clears it after execution'() {
    expect:
    testedProcess.waitFor(TIMEOUT_SECS, SECONDS)
    testedProcess.exitValue() == 0
    waitForTrace(defaultPoll, checkTrace())
    waitForTrace(defaultPoll, { trace ->
      trace.spans.size() == 1 && findRootSpan(trace, 'unrelated-forkjoin')
    })
    waitForTrace(defaultPoll, { trace ->
      trace.spans.size() == 1 && findRootSpan(trace, 'unrelated-executor')
    })
    traceCount.get() == 3
  }
}
