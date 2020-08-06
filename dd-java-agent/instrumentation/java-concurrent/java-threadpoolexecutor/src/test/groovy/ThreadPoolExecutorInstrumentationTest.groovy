import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import spock.lang.Shared

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static org.junit.Assume.assumeTrue

class ThreadPoolExecutorInstrumentationTest extends AgentTestRunner {
  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.integration.thread-pool-executor.enabled", "false")
    }
  }

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def invokeAll = { e, c -> e.invokeAll([(Callable) c]) }
  @Shared
  def invokeAllTimeout = { e, c -> e.invokeAll([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeAny = { e, c -> e.invokeAny([(Callable) c]) }
  @Shared
  def invokeAnyTimeout = { e, c -> e.invokeAny([(Callable) c], 10, TimeUnit.SECONDS) }

  def "#poolImpl '#name' disabled"() {
    setup:
    assumeTrue(poolImpl != null) // skip for Java 7 CompletableFuture
    def pool = poolImpl
    def m = method

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      void run() {
        activeScope().setAsyncPropagation(true)
        // this child will have a span
        m(pool, new JavaAsyncChild())
        // this child won't
        m(pool, new JavaAsyncChild(false, false))
        blockUntilChildSpansFinished(1)
      }
    }.run()

    TEST_WRITER.waitForTraces(1)
    List<DDSpan> trace = TEST_WRITER.get(0)

    expect:
    TEST_WRITER.size() == 1
    trace.size() == 2
    trace.get(0).operationName == "parent"
    trace.get(1).operationName == "asyncChild"
    trace.get(1).parentId == trace.get(0).spanId

    cleanup:
    if (pool?.hasProperty("shutdown")) {
      pool?.shutdown()
    }

    // Unfortunately, there's no simple way to test the cross product of methods/pools.
    where:
    name                     | method           | poolImpl
    "execute Runnable"       | executeRunnable  | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Runnable"        | submitRunnable   | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"        | submitCallable   | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll"              | invokeAll        | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll with timeout" | invokeAllTimeout | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny"              | invokeAny        | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny with timeout" | invokeAnyTimeout | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
  }
}
