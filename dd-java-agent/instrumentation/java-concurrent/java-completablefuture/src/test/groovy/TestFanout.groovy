import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.Checkpointer.CPU
import static datadog.trace.api.Checkpointer.END
import static datadog.trace.api.Checkpointer.SPAN
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION

class TestFanout extends AgentTestRunner {

  def "test propagate with fanout"() {
    setup:
    CheckpointValidator.excludeValidations(
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    runUnderTrace("parent") {
      new Fanout(executor, 3, true).execute()
    }
    then:
    assertTraces(1) {
      trace(4) {
        span(0) {
          resourceName "parent"
        }
        span(1) {
          resourceName "Fanout.tracedWork"
          childOf span(0)
        }
        span(2) {
          resourceName "Fanout.tracedWork"
          childOf span(0)
        }
        span(3) {
          resourceName "Fanout.tracedWork"
          childOf span(0)
        }
      }
    }

    cleanup:
    executor.shutdownNow()

    where:
    executor << [
      Executors.newSingleThreadExecutor(),
      Executors.newFixedThreadPool(3),
      ForkJoinPool.commonPool()
    ]
  }

  def "test completablefuture fanout checkpoints"() {
    setup:
    CheckpointValidator.excludeValidations(
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    runUnderTrace("parent") {
      new Fanout(executor, 3, traceChildTasks).execute()
    }
    then:
    TEST_WRITER.waitForTraces(1)
    (traceChildTasks ? 4 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN)
    3 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION)
    3 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, _, CPU | END)
    (traceChildTasks ? 4 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN | END)

    cleanup:
    executor.shutdownNow()

    where:
    executor                            | traceChildTasks
    Executors.newSingleThreadExecutor() | true
    Executors.newFixedThreadPool(3)     | true
    ForkJoinPool.commonPool()           | true
    Executors.newSingleThreadExecutor() | false
    Executors.newFixedThreadPool(3)     | false
    ForkJoinPool.commonPool()           | false
  }

  def "test completablefuture two level fanout checkpoints"() {
    setup:
    CheckpointValidator.excludeValidations(
      CheckpointValidationMode.THREAD_SEQUENCE)

    when:
    runUnderTrace("parent") {
      new Fanout(executor, 3, traceChildTasks).executeTwoLevels()
    }
    then:
    TEST_WRITER.waitForTraces(1)
    (traceChildTasks ? 7 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN)
    6 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION)
    6 * TEST_CHECKPOINTER.checkpoint(_, _, THREAD_MIGRATION | END)
    _ * TEST_CHECKPOINTER.checkpoint(_, _, CPU | END)
    (traceChildTasks ? 7 : 1) * TEST_CHECKPOINTER.checkpoint(_, _, SPAN | END)

    cleanup:
    executor.shutdownNow()

    where:
    executor                            | traceChildTasks
    Executors.newSingleThreadExecutor() | true
    Executors.newFixedThreadPool(3)     | true
    ForkJoinPool.commonPool()           | true
    Executors.newSingleThreadExecutor() | false
    Executors.newFixedThreadPool(3)     | false
    ForkJoinPool.commonPool()           | false
  }
}
