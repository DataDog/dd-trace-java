import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.timer.TestTimer
import io.netty.util.concurrent.DefaultEventExecutorGroup

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class TimingTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.profiling.experimental.queueing.time.enabled", "true")
    super.configurePreAgent()
  }

  def "test queue timing with submit"() {
    setup:
    DefaultEventExecutorGroup defaultEventExecutorGroup = new DefaultEventExecutorGroup(1)

    when:
    runUnderTrace("parent", {
      defaultEventExecutorGroup.submit(new TestRunnable()).get()
    })

    then:
    verify()

    cleanup:
    defaultEventExecutorGroup.shutdownGracefully()
    TEST_TIMER.closedTimings.clear()
  }

  def "test queue timing with schedule"() {
    setup:
    DefaultEventExecutorGroup defaultEventExecutorGroup = new DefaultEventExecutorGroup(1)

    when:
    runUnderTrace("parent", {
      defaultEventExecutorGroup.schedule(new TestRunnable(), 1, TimeUnit.SECONDS).get()
    })

    then:
    verify()

    cleanup:
    defaultEventExecutorGroup.shutdownGracefully()
    TEST_TIMER.closedTimings.clear()
  }

  void verify() {
    assert TEST_TIMER.isBalanced()
    assert !TEST_TIMER.closedTimings.isEmpty()
    int numAsserts = 0
    while (!TEST_TIMER.closedTimings.isEmpty()) {
      def timing = TEST_TIMER.closedTimings.takeFirst() as TestTimer.TestQueueTiming
      // filter out any netty chores, filtering these out by class name in the instrumentation
      // may be too expensive. They should get filtered out by duration anyway.
      if (!(timing.task as Class).simpleName.isEmpty()) {
        assert timing != null
        assert timing.task == TestRunnable
        assert timing.scheduler == DefaultEventExecutorGroup
        assert timing.origin == Thread.currentThread()
        numAsserts++
      }
    }
    assert numAsserts > 0
  }


  class TestRunnable implements Runnable {
    @Override
    void run() {}
  }
}
