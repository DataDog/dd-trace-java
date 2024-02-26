import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper
import datadog.trace.core.DDSpan
import org.apache.tomcat.util.threads.TaskQueue
import spock.lang.Shared
import spock.lang.Unroll

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.Future
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static org.junit.Assume.assumeTrue

class VirtualThreadTest extends AgentTestRunner {

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def executePriorityTask = { e, c -> e.execute(new ComparableAsyncChild(0, (Runnable) c)) }
  @Shared
  def executeForkJoinTask = { e, c -> e.execute((ForkJoinTask) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }
  @Shared
  def submitCallable = { e, c -> e.submit((Callable) c) }
  @Shared
  def submitRunnableExecutorCompletionService = { ecs, c -> ecs.submit((Runnable) c, null) }
  @Shared
  def submitForkJoinTask = { e, c -> e.submit((ForkJoinTask) c) }
  @Shared
  def invokeAll = { e, c -> e.invokeAll([(Callable) c]) }
  @Shared
  def invokeAllTimeout = { e, c -> e.invokeAll([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeAny = { e, c -> e.invokeAny([(Callable) c]) }
  @Shared
  def invokeAnyTimeout = { e, c -> e.invokeAny([(Callable) c], 10, TimeUnit.SECONDS) }
  @Shared
  def invokeForkJoinTask = { e, c -> e.invoke((ForkJoinTask) c) }
  @Shared
  def scheduleRunnable = { e, c -> e.schedule((Runnable) c, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleCallable = { e, c -> e.schedule((Callable) c, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleAtFixedRate = { e, c -> e.scheduleAtFixedRate((Runnable) c, 10, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleWithFixedDelay = { e, c -> e.scheduleWithFixedDelay((Runnable) c, 10, 10, TimeUnit.MILLISECONDS) }

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.executors", "CustomThreadPoolExecutor")
   // injectSysConfig("trace.thread-pool-executors.exclude", "ExecutorInstrumentationTest\$ToBeIgnoredExecutor")
  }

  //@Unroll
  def "virtualThreadPool"() {
    //def "#poolName '#name' propagates"() {
    setup:
    assumeTrue(poolImpl != null) // skip for Java 7 CompletableFuture, non-Linux Netty EPoll
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
    "execute ForkJoinTask"   | executeForkJoinTask | Executors.newVirtualThreadPerTaskExecutor()
    "submit Runnable"        | submitRunnable      | Executors.newVirtualThreadPerTaskExecutor()
    "submit Callable"        | submitCallable      | Executors.newVirtualThreadPerTaskExecutor()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(Executors.newVirtualThreadPerTaskExecutor())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(Executors.newVirtualThreadPerTaskExecutor())
    "submit ForkJoinTask"    | submitForkJoinTask  | Executors.newVirtualThreadPerTaskExecutor()
    "invoke ForkJoinTask"    | invokeForkJoinTask  | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAll"              | invokeAll           | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAny"              | invokeAny           | Executors.newVirtualThreadPerTaskExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | Executors.newVirtualThreadPerTaskExecutor()

    // spotless:on
  }


}


//class ExecutorInstrumentationForkedTest extends VirtualThreadTest {
//  def setupSpec() {
//    System.setProperty("dd.trace.thread-pool-executors.legacy.tracing.enabled", "false")
//  }
//}
//
//class ExecutorInstrumentationLegacyForkedTest extends VirtualThreadTest {
//  def setupSpec() {
//    System.setProperty("dd.trace.thread-pool-executors.legacy.tracing.enabled", "true")
//  }
//}
//
//class ExecutorInstrumentationQueueTimeForkedTest extends VirtualThreadTest {
//  def setupSpec() {
//    System.setProperty("dd.profiling.enabled", "true")
//    System.setProperty("dd.profiling.experimental.queueing.time.enabled", "true")
//  }
//}
