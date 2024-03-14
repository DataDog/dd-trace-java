import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import spock.lang.Shared

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class VirtualThreadTest extends AgentTestRunner {

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def submitRunnableExecutorCompletionService = { ecs, c -> ecs.submit((Runnable) c, null) }
  @Shared
  def invokeAll = { e, c -> e.invokeAll([(Callable) c]) }
  @Shared
  def invokeAllTimeout = { e, c -> e.invokeAll([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeAny = { e, c -> e.invokeAny([(Callable) c]) }
  @Shared
  def invokeAnyTimeout = { e, c -> e.invokeAny([(Callable) c], 10, TimeUnit.SECONDS) }

  def "virtualThreadPool #name"() {
    setup:
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

    where:
    // spotless:off
    name                     | method              | poolImpl
    "execute Runnable"       | executeRunnable     | Executors.newVirtualThreadPerTaskExecutor()
    "submit Runnable"        | submitRunnable      | Executors.newVirtualThreadPerTaskExecutor()
    "submit Callable"        | submitCallable      | Executors.newVirtualThreadPerTaskExecutor()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(Executors.newVirtualThreadPerTaskExecutor())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(Executors.newVirtualThreadPerTaskExecutor())
    "invokeAll"              | invokeAll           | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAny"              | invokeAny           | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | Executors.newVirtualThreadPerTaskExecutor()
     // spotless:on
  }
}
