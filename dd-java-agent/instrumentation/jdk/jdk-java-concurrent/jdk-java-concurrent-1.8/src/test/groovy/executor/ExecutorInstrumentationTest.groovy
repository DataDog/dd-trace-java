package executor

import com.google.common.util.concurrent.MoreExecutors
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper
import datadog.trace.core.DDSpan
import forkjoin.PeriodicTask
import org.apache.tomcat.util.threads.TaskQueue
import runnable.ComparableAsyncChild
import runnable.JavaAsyncChild
import spock.lang.Shared

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

import static org.junit.jupiter.api.Assumptions.assumeTrue

abstract class ExecutorInstrumentationTest extends InstrumentationSpecification {

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

    injectSysConfig("dd.trace.executors", CustomThreadPoolExecutor.name)
    injectSysConfig("trace.thread-pool-executors.exclude", ToBeIgnoredExecutor.name)
  }

  def "#poolName '#name' propagates"() {
    setup:
    assumeTrue(poolImpl != null) // skip for Java 7 CompletableFuture, non-Linux Netty EPoll
    def pool = poolImpl
    def m = method

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
    if (pool?.hasProperty("shutdown")) {
      pool?.shutdown()
    }

    // Unfortunately, there's no simple way to test the cross product of methods/pools.
    where:
    // spotless:off
    name                     | method              | poolImpl
    "execute Runnable"       | executeRunnable     | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Runnable"        | submitRunnable      | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"        | submitCallable      | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1)))
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1)))
    "invokeAll"              | invokeAll           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll with timeout" | invokeAllTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny"              | invokeAny           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny with timeout" | invokeAnyTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))

    "execute Priority task"  | executePriorityTask | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new PriorityBlockingQueue<ComparableAsyncChild>(10))

    // Scheduled executor has additional methods and also may get disabled because it wraps tasks
    "execute Runnable"       | executeRunnable     | new ScheduledThreadPoolExecutor(1)
    "submit Runnable"        | submitRunnable      | new ScheduledThreadPoolExecutor(1)
    "submit Callable"        | submitCallable      | new ScheduledThreadPoolExecutor(1)
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(new ScheduledThreadPoolExecutor(1))
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(new ScheduledThreadPoolExecutor(1))
    "invokeAll"              | invokeAll           | new ScheduledThreadPoolExecutor(1)
    "invokeAll with timeout" | invokeAllTimeout    | new ScheduledThreadPoolExecutor(1)
    "invokeAny"              | invokeAny           | new ScheduledThreadPoolExecutor(1)
    "invokeAny with timeout" | invokeAnyTimeout    | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable"      | scheduleRunnable    | new ScheduledThreadPoolExecutor(1)
    "schedule Callable"      | scheduleCallable    | new ScheduledThreadPoolExecutor(1)

    // ForkJoinPool has additional set of method overloads for ForkJoinTask to deal with
    "execute Runnable"       | executeRunnable     | new ForkJoinPool()
    "execute ForkJoinTask"   | executeForkJoinTask | new ForkJoinPool()
    "submit Runnable"        | submitRunnable      | new ForkJoinPool()
    "submit Callable"        | submitCallable      | new ForkJoinPool()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(new ForkJoinPool())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(new ForkJoinPool())
    "submit ForkJoinTask"    | submitForkJoinTask  | new ForkJoinPool()
    "invoke ForkJoinTask"    | invokeForkJoinTask  | new ForkJoinPool()
    "invokeAll"              | invokeAll           | new ForkJoinPool()
    "invokeAll with timeout" | invokeAllTimeout    | new ForkJoinPool()
    "invokeAny"              | invokeAny           | new ForkJoinPool()
    "invokeAny with timeout" | invokeAnyTimeout    | new ForkJoinPool()

    // CustomThreadPoolExecutor would normally be disabled except enabled above.
    "execute Runnable"       | executeRunnable     | new CustomThreadPoolExecutor()
    "submit Runnable"        | submitRunnable      | new CustomThreadPoolExecutor()
    "submit Callable"        | submitCallable      | new CustomThreadPoolExecutor()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(new CustomThreadPoolExecutor())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(new CustomThreadPoolExecutor())
    "invokeAll"              | invokeAll           | new CustomThreadPoolExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | new CustomThreadPoolExecutor()
    "invokeAny"              | invokeAny           | new CustomThreadPoolExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | new CustomThreadPoolExecutor()


    "execute Runnable"       | executeRunnable     | new TypeAwareThreadPoolExecutor()
    "submit Runnable"        | submitRunnable      | new TypeAwareThreadPoolExecutor()
    "submit Callable"        | submitCallable      | new TypeAwareThreadPoolExecutor()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(new TypeAwareThreadPoolExecutor())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(new TypeAwareThreadPoolExecutor())
    "invokeAll"              | invokeAll           | new TypeAwareThreadPoolExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | new TypeAwareThreadPoolExecutor()
    "invokeAny"              | invokeAny           | new TypeAwareThreadPoolExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | new TypeAwareThreadPoolExecutor()

    // java.util.concurrent.Executors$FinalizableDelegatedExecutorService
    "execute Runnable"       | executeRunnable     | Executors.newSingleThreadExecutor()
    "submit Runnable"        | submitRunnable      | Executors.newSingleThreadExecutor()
    "submit Callable"        | submitCallable      | Executors.newSingleThreadExecutor()
    "submit Runnable ECS"    | submitRunnableExecutorCompletionService | new ExecutorCompletionService<>(Executors.newSingleThreadExecutor())
    "submit Callable ECS"    | submitCallable      | new ExecutorCompletionService<>(Executors.newSingleThreadExecutor())
    "invokeAll"              | invokeAll           | Executors.newSingleThreadExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | Executors.newSingleThreadExecutor()
    "invokeAny"              | invokeAny           | Executors.newSingleThreadExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | Executors.newSingleThreadExecutor()

    // java.util.concurrent.Executors$DelegatedExecutorService
    "execute Runnable"       | executeRunnable     | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "submit Runnable"        | submitRunnable      | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "submit Callable"        | submitCallable      | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "invokeAll"              | invokeAll           | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "invokeAll with timeout" | invokeAllTimeout    | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "invokeAny"              | invokeAny           | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "invokeAny with timeout" | invokeAnyTimeout    | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())


    "execute Runnable"       | executeRunnable     | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "submit Runnable"        | submitRunnable      | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "submit Callable"        | submitCallable      | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "invokeAll"              | invokeAll           | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "invokeAll with timeout" | invokeAllTimeout    | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "invokeAny"              | invokeAny           | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "invokeAny with timeout" | invokeAnyTimeout    | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "schedule Runnable"      | scheduleRunnable    | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "schedule Callable"      | scheduleCallable    | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())

    // tomcat
    "execute Runnable"       | executeRunnable     | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "submit Runnable"        | submitRunnable      | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "submit Callable"        | submitCallable      | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "invokeAll"              | invokeAll           | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "invokeAll with timeout" | invokeAllTimeout    | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "invokeAny"              | invokeAny           | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "invokeAny with timeout" | invokeAnyTimeout    | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())

    // guava
    "execute Runnable"       | executeRunnable     | MoreExecutors.directExecutor()

    "execute Runnable"       | executeRunnable     | MoreExecutors.newDirectExecutorService()
    "submit Runnable"        | submitRunnable      | MoreExecutors.newDirectExecutorService()
    "submit Callable"        | submitCallable      | MoreExecutors.newDirectExecutorService()
    "invokeAll"              | invokeAll           | MoreExecutors.newDirectExecutorService()
    "invokeAll with timeout" | invokeAllTimeout    | MoreExecutors.newDirectExecutorService()
    "invokeAny"              | invokeAny           | MoreExecutors.newDirectExecutorService()
    "invokeAny with timeout" | invokeAnyTimeout    | MoreExecutors.newDirectExecutorService()

    "execute Runnable"       | executeRunnable     | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    "submit Runnable"        | submitRunnable      | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    "submit Callable"        | submitCallable      | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    "invokeAll"              | invokeAll           | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    "invokeAll with timeout" | invokeAllTimeout    | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    "invokeAny"              | invokeAny           | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    "invokeAny with timeout" | invokeAnyTimeout    | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    "execute Runnable"       | executeRunnable     | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "submit Runnable"        | submitRunnable      | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "submit Callable"        | submitCallable      | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "invokeAll"              | invokeAll           | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "invokeAll with timeout" | invokeAllTimeout    | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "invokeAny"              | invokeAny           | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "invokeAny with timeout" | invokeAnyTimeout    | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "schedule Runnable"      | scheduleRunnable    | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    "schedule Callable"      | scheduleCallable    | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    // spotless:on
    poolName = poolImpl.class.simpleName
  }

  def "#poolName '#name' doesn't propagate"() {
    setup:
    def pool = poolImpl
    def m = method
    def task = new PeriodicTask()

    when:
    // make sure that the task is removed from the queue on cancel
    pool.setRemoveOnCancelPolicy(true)
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          def future = m(pool, task)
          sleep(500)
          future.cancel(true)
          while (!future.isDone()) {
            sleep(500)
          }
        }
      }.run()
    // there is a potential race where the task is still executing or about to execute
    task.ensureFinished()
    def runCount = task.runCount

    then:
    assertTraces(runCount + 1) {
      sortSpansByStart()
      trace(1) {
        span {
          operationName "parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
      for (int i = 0; i < runCount; i++) {
        trace(1) {
          span {
            operationName "periodicRun"
            parent()
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    cleanup:
    if (pool?.hasProperty("shutdown")) {
      pool?.shutdown()
    }

    where:
    // spotless:off
    name                        | method                 | poolImpl
    "schedule at fixed rate"    | scheduleAtFixedRate    | new ScheduledThreadPoolExecutor(1)
    "schedule with fixed delay" | scheduleWithFixedDelay | new ScheduledThreadPoolExecutor(1)
    // spotless:on
    poolName = poolImpl.class.simpleName
  }

  def "excluded ToBeIgnoredExecutor doesn't propagate"() {
    setup:
    def pool = new ToBeIgnoredExecutor()
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          // this child will have a span
          pool.execute(new JavaAsyncChild())
          // this child won't
          pool.execute(new JavaAsyncChild(false, false))
        }
      }.run()
    TEST_WRITER.waitForTraces(2)

    expect:
    assertTraces(2) {
      sortSpansByStart()
      trace(1) {
        span {
          operationName "parent"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          operationName "asyncChild"
          parent()
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
      }
    }

    cleanup:
    if (pool?.hasProperty("shutdown")) {
      pool?.shutdown()
    }
  }

  def "#poolName '#name' wraps"() {
    setup:
    def pool = poolImpl
    def m = method
    def w = wrap

    JavaAsyncChild child = new JavaAsyncChild(true, true)
    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          m(pool, w(child))
        }
      }.run()
    child.unblock()

    TEST_WRITER.waitForTraces(1)

    expect:
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 2
    TEST_WRITER.get(0).get(1).operationName == "parent"
    TEST_WRITER.get(0).get(0).operationName == "asyncChild"
    TEST_WRITER.get(0).get(0).parentId == TEST_WRITER.get(0).get(1).spanId

    cleanup:
    pool?.shutdown()

    where:
    name                | method           | wrap                        | poolImpl
    "execute Runnable"  | executeRunnable  | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "submit Runnable"   | submitRunnable   | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable" | scheduleRunnable | { new RunnableWrapper(it) } | new ScheduledThreadPoolExecutor(1)
    poolName = poolImpl.class.simpleName
  }

  def "#poolName '#name' reports after canceled jobs"() {
    setup:
    assumeTrue(poolImpl != null) // skip for non-Linux Netty EPoll
    def pool = poolImpl
    def m = method
    List<JavaAsyncChild> children = new ArrayList<>()
    List<Future> jobFutures = new ArrayList<>()

    new Runnable() {
        @Override
        @Trace(operationName = "parent")
        void run() {
          try {
            for (int i = 0; i < 20; ++i) {
              final JavaAsyncChild child = new JavaAsyncChild(false, true)
              children.add(child)
              try {
                Future f = m(pool, child)
                jobFutures.add(f)
              } catch (InvocationTargetException e) {
                throw e.getCause()
              }
            }
          } catch (RejectedExecutionException ignored) {
          }

          for (Future f : jobFutures) {
            f.cancel(false)
          }
          for (JavaAsyncChild child : children) {
            child.unblock()
          }
        }
      }.run()

    TEST_WRITER.waitForTraces(1)

    expect:
    // FIXME: we should improve this test to make sure continuations are actually closed
    TEST_WRITER.size() == 1

    cleanup:
    if (pool?.hasProperty("shutdown")) {
      pool?.shutdown()
    }

    where:
    name                  | method             | poolImpl
    "submit Runnable"     | submitRunnable     | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"     | submitCallable     | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))

    // Scheduled executor has additional methods and also may get disabled because it wraps tasks
    "submit Runnable"     | submitRunnable     | new ScheduledThreadPoolExecutor(1)
    "submit Callable"     | submitCallable     | new ScheduledThreadPoolExecutor(1)
    "schedule Runnable"   | scheduleRunnable   | new ScheduledThreadPoolExecutor(1)
    "schedule Callable"   | scheduleCallable   | new ScheduledThreadPoolExecutor(1)

    // ForkJoinPool has additional set of method overloads for ForkJoinTask to deal with
    "submit Runnable"     | submitRunnable     | new ForkJoinPool()
    "submit Callable"     | submitCallable     | new ForkJoinPool()
    "submit ForkJoinTask" | submitForkJoinTask | new ForkJoinPool()

    // java.util.concurrent.Executors$FinalizableDelegatedExecutorService
    "submit Runnable"     | submitRunnable     | Executors.newSingleThreadExecutor()
    "submit Callable"     | submitCallable     | Executors.newSingleThreadExecutor()

    // java.util.concurrent.Executors$DelegatedExecutorService
    "submit Runnable"     | submitRunnable     | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())
    "submit Callable"     | submitCallable     | Executors.unconfigurableExecutorService(Executors.newSingleThreadExecutor())


    "submit Runnable"     | submitRunnable     | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "submit Callable"     | submitCallable     | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "schedule Runnable"   | scheduleRunnable   | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())
    "schedule Callable"   | scheduleCallable   | Executors.unconfigurableScheduledExecutorService(Executors.newSingleThreadScheduledExecutor())

    // tomcat
    "submit Runnable"     | submitRunnable     | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())
    "submit Callable"     | submitCallable     | new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue())

    // guava
    // FIXME - these need better rejection handling to pass reliably
    //    "submit Runnable"     | submitRunnable     | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    //    "submit Callable"     | submitCallable     | MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    //    "submit Runnable"     | submitRunnable     | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    //    "submit Callable"     | submitCallable     | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    //    "schedule Runnable"   | scheduleRunnable   | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())
    //    "schedule Callable"   | scheduleCallable   | MoreExecutors.listeningDecorator(Executors.newSingleThreadScheduledExecutor())

    poolName = poolImpl.class.simpleName
  }
}

class ExecutorInstrumentationForkedTest extends ExecutorInstrumentationTest {
  def setupSpec() {
    System.setProperty("dd.trace.thread-pool-executors.legacy.tracing.enabled", "false")
  }
}

class ExecutorInstrumentationLegacyForkedTest extends ExecutorInstrumentationTest {
  def setupSpec() {
    System.setProperty("dd.trace.thread-pool-executors.legacy.tracing.enabled", "true")
  }
}

class ExecutorInstrumentationQueueTimeForkedTest extends ExecutorInstrumentationTest {
  def setupSpec() {
    System.setProperty("dd.profiling.enabled", "true")
    System.setProperty("dd.profiling.queueing.time.enabled", "true")
  }
}
