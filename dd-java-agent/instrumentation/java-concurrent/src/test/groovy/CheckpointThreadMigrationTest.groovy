import datadog.trace.agent.test.AgentTestRunner
import io.netty.util.concurrent.DefaultEventExecutorGroup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION
import static java.util.concurrent.TimeUnit.SECONDS

class CheckpointThreadMigrationTest extends AgentTestRunner {

  def "emit checkpoints on #executor submission"() {
    when:
    runUnderTrace("parent") {
      executor.submit(new CheckpointTask(traceChildTasks)).get()
    }
    then:
    TEST_WRITER.waitForTraces(1)
    (traceChildTasks ? 2 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN)
    1 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION)
    1 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, _, CPU | END)
    (traceChildTasks ? 2 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN | END)

    cleanup:
    executor.shutdownNow()

    where:
    executor                            | traceChildTasks
    Executors.newScheduledThreadPool(1) | true
    Executors.newFixedThreadPool(1)     | true
    new DefaultEventExecutorGroup(1)    | true
    Executors.newScheduledThreadPool(1) | false
    Executors.newFixedThreadPool(1)     | false
    new DefaultEventExecutorGroup(1)    | false
  }

  def "emit checkpoints on #executor execution"() {
    // execution is typically handled differently than when a future
    // is produced
    when:
    runUnderTrace("parent") {
      CountDownLatch latch = new CountDownLatch(1)
      executor.execute(new CheckpointTask(traceChildTasks, latch))
      latch.await(30, SECONDS)
    }
    then:
    TEST_WRITER.waitForTraces(1)
    (traceChildTasks ? 2 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN)
    1 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION)
    1 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, _, CPU | END)
    (traceChildTasks ? 2 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN | END)

    cleanup:
    executor.shutdownNow()

    where:
    executor                            | traceChildTasks
    Executors.newScheduledThreadPool(1) | true
    Executors.newFixedThreadPool(1)     | true
    new DefaultEventExecutorGroup(1)    | true
    Executors.newScheduledThreadPool(1) | false
    Executors.newFixedThreadPool(1)     | false
    new DefaultEventExecutorGroup(1)    | false
  }
}
