import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import org.eclipse.jetty.util.thread.MonitoredQueuedThreadPool
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.ReservedThreadExecutor
import spock.lang.Shared

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static org.junit.jupiter.api.Assumptions.assumeTrue

class JettyExecutorInstrumentationTest extends InstrumentationSpecification {

  @Shared
  ExecutorService exHolder = null

  def delegate() {
    // We need lazy init, or else the constructor is run before the instrumentation is applied
    return exHolder == null ? exHolder = Executors.newSingleThreadExecutor() : exHolder
  }

  @Override
  def cleanupSpec() {
    delegate().shutdownNow()
  }

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def invokeAll = { e, c -> e.invokeAll([(Callable) c]) }
  @Shared
  def invokeAny = { e, c -> e.invokeAny([(Callable) c]) }

  def "#poolName '#name' propagates"() {
    setup:
    assumeTrue(poolImpl != null) // skip for Java 7 CompletableFuture, non-Linux Netty EPoll
    def pool = poolImpl
    def m = method
    pool.start()

    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
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
    pool.stop()

    // Unfortunately, there's no simple way to test the cross product of methods/pools.
    where:
    name                     | method              | poolImpl
    "execute Runnable"       | executeRunnable     | new MonitoredQueuedThreadPool(8)
    "execute Runnable"       | executeRunnable     | new QueuedThreadPool(8)
    "execute Runnable"       | executeRunnable     | new ReservedThreadExecutor(delegate(), 1)
    poolName = poolImpl.class.simpleName
  }
}
