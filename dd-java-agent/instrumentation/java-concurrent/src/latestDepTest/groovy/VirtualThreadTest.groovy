import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import spock.lang.Shared

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.TimeUnit

class VirtualThreadTest extends AgentTestRunner {

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
//  @Shared
//  def executeForkJoinTask = { e, c -> e.execute((ForkJoinTask) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def submitRunnableExecutorCompletionService = { ecs, c -> ecs.submit((Runnable) c, null) }
//  @Shared
//  def submitForkJoinTask = { e, c -> e.submit((ForkJoinTask) c) }
  @Shared
  def invokeAll = { e, c -> e.invokeAll([(Callable) c]) }
  @Shared
  def invokeAllTimeout = { e, c -> e.invokeAll([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeAny = { e, c -> e.invokeAny([(Callable) c]) }
  @Shared
  def invokeAnyTimeout = { e, c -> e.invokeAny([(Callable) c], 10, TimeUnit.SECONDS) }
//  @Shared
//  def invokeForkJoinTask = { e, c -> e.invoke((ForkJoinTask) c) }
//  @Shared
//  def scheduleRunnable = { e, c -> e.schedule((Runnable) c, 10, TimeUnit.MILLISECONDS) }
//  @Shared
//  def scheduleCallable = { e, c -> e.schedule((Callable) c, 10, TimeUnit.MILLISECONDS) }
//  @Shared
//  def scheduleAtFixedRate = { e, c -> e.scheduleAtFixedRate((Runnable) c, 10, 10, TimeUnit.MILLISECONDS) }
//  @Shared
//  def scheduleWithFixedDelay = { e, c -> e.scheduleWithFixedDelay((Runnable) c, 10, 10, TimeUnit.MILLISECONDS) }

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
//    "execute Runnable"       | executeRunnable     | Executors.newVirtualThreadPerTaskExecutor()
    "submit Runnable"        | submitRunnable      | Executors.newVirtualThreadPerTaskExecutor()
    "submit Callable"        | submitCallable      | Executors.newVirtualThreadPerTaskExecutor()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(Executors.newVirtualThreadPerTaskExecutor())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(Executors.newVirtualThreadPerTaskExecutor())
    "invokeAll"              | invokeAll           | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | Executors.newVirtualThreadPerTaskExecutor()

    "invokeAny"              | invokeAny           | Executors.newVirtualThreadPerTaskExecutor()
 //   "invokeAny with timeout" | invokeAnyTimeout    | Executors.newVirtualThreadPerTaskExecutor()

//    "xinvokeAll"              | invokeAll           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
//    "xinvokeAll with timeout" | invokeAllTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
//    "xinvokeAny"              | invokeAny           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
//    "xinvokeAny with timeout" | invokeAnyTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
//
//    "execute Runnable"       | executeRunnable     | Executors.newFixedThreadPool(10)
//    "submit Runnable"        | submitRunnable      | Executors.newFixedThreadPool(10)
//    "submit Callable"        | submitCallable      | Executors.newFixedThreadPool(10)
//    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(Executors.newFixedThreadPool(10))
//    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(Executors.newFixedThreadPool(10))
//    "invokeAll"              | invokeAll           | Executors.newFixedThreadPool(10)
//    "invokeAll with timeout" | invokeAllTimeout    | Executors.newFixedThreadPool(10)
//    "invokeAny"              | invokeAny           | Executors.newFixedThreadPool(10)
//    "invokeAny with timeout" | invokeAnyTimeout    | Executors.newFixedThreadPool(10)

    // spotless:on
  }
}
