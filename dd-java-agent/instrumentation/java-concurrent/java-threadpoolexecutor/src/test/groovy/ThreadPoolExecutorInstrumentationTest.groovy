import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.java.concurrent.CallableWrapper
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper
import spock.lang.Shared

import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class ThreadPoolExecutorInstrumentationTest extends AgentTestRunner {
  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.trace.thread_pool_executor_wrapped_tasks.enabled", "false")
    }
  }

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def scheduleRunnable = { e, c -> e.schedule((Runnable) c, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleCallable = { e, c -> e.schedule((Callable) c, 10, TimeUnit.MILLISECONDS) }

  def "#poolImpl '#name' disabled"() {
    setup:
    def pool = poolImpl
    def m = method
    def w = wrap

    JavaAsyncChild child = new JavaAsyncChild(true, true)
    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      void run() {
        activeScope().setAsyncPropagation(true)
        m(pool, w(child))
      }
    }.run()
    // We block in child to make sure spans close in predictable order
    child.unblock()

    // Expect two traces because async propagation gets effectively disabled
    TEST_WRITER.waitForTraces(2)

    expect:
    TEST_WRITER.size() == 2
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).operationName == "parent"
    TEST_WRITER.get(1).size() == 1
    TEST_WRITER.get(1).get(0).operationName == "asyncChild"

    cleanup:
    pool?.shutdown()

    where:
    // Scheduled executor cannot accept wrapped tasks
    // TODO: we should have a test that passes lambda, but this is hard
    // because this requires tests to be run in java8+ only.
    // Instead we 'hand-wrap' tasks in this test.
    name                | method           | wrap                        | poolImpl
    "execute Runnable"  | executeRunnable  | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Runnable"   | submitRunnable   | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Callable"   | submitCallable   | { new CallableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable" | scheduleRunnable | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Callable" | scheduleCallable | { new CallableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
  }
}
