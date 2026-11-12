package executor

import static executor.NettyTestExecutors.shutdown

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanId
import datadog.trace.core.DDSpan
import executor.rejectedexecutionhandler.NettySwallowingRejectedExecutionHandler
import io.netty.util.concurrent.DefaultEventExecutor
import io.netty.util.concurrent.DefaultThreadFactory
import java.util.concurrent.CountDownLatch
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class NettyRejectedExecutionTest extends InstrumentationSpecification {

  def "trace reported with swallowing netty rejected execution handler" () {
    setup:
    DefaultEventExecutor executor = new DefaultEventExecutor(null, new DefaultThreadFactory(DefaultEventExecutor),
      1, handler)
    CountDownLatch latch = new CountDownLatch(1)
    // this task will block the executor thread ensuring the next task gets enqueued
    executor.submit({
      latch.await()
    })
    // this will sit in the queue
    executor.submit({})

    when:
    runUnderTrace("parent") {
      // must be rejected because the queue will be full until some
      // time after the first task is released
      executor.submit((Runnable) new NettyAsyncChild(true, false))
      latch.countDown()
    }

    then:
    expectParentChildTrace()

    cleanup:
    latch?.countDown()
    shutdown(executor)

    where:
    handler << [new NettySwallowingRejectedExecutionHandler()]
  }

  def expectParentChildTrace() {
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    List<DDSpan> trace = TEST_WRITER.get(0)
    assert trace.size() == 2
    DDSpan parent = trace.find {
      it.getOperationName() == "parent"
    }
    DDSpan child = trace.find {
      it.getOperationName() == "asyncChild"
    }
    assert parent != null
    assert child != null
    assert parent.getParentId() == DDSpanId.ZERO
    assert parent.getSpanId() == child.getParentId()
    return true
  }
}
