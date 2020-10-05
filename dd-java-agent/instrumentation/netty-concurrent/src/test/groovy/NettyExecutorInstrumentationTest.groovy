import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.local.LocalEventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.UnorderedThreadPoolEventExecutor
import spock.lang.Shared

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static org.junit.Assume.assumeTrue

class NettyExecutorInstrumentationTest extends AgentTestRunner {

  @Shared
  boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux")

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
  @Shared
  def scheduleRunnable = { e, c -> e.schedule((Runnable) c, 10, TimeUnit.MILLISECONDS) }
  @Shared
  def scheduleCallable = { e, c -> e.schedule((Callable) c, 10, TimeUnit.MILLISECONDS) }

  def "#poolImpl '#name' propagates"() {
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
    name                     | method              | poolImpl
    "execute Runnable"       | executeRunnable     | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Runnable"        | submitRunnable      | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"        | submitCallable      | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll"              | invokeAll           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAll with timeout" | invokeAllTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny"              | invokeAny           | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "invokeAny with timeout" | invokeAnyTimeout    | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))

    // Scheduled executor has additional methods and also may get disabled because it wraps tasks
    "execute Runnable"       | executeRunnable     | new DefaultEventExecutorGroup(1).next()
    "submit Runnable"        | submitRunnable      | new DefaultEventExecutorGroup(1).next()
    "submit Callable"        | submitCallable      | new DefaultEventExecutorGroup(1).next()
    "invokeAll"              | invokeAll           | new DefaultEventExecutorGroup(1).next()
    "invokeAll with timeout" | invokeAllTimeout    | new DefaultEventExecutorGroup(1).next()
    "invokeAny"              | invokeAny           | new DefaultEventExecutorGroup(1).next()
    "invokeAny with timeout" | invokeAnyTimeout    | new DefaultEventExecutorGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new DefaultEventExecutorGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new DefaultEventExecutorGroup(1).next()

    "execute Runnable"       | executeRunnable     | new DefaultEventLoopGroup(1).next()
    "submit Runnable"        | submitRunnable      | new DefaultEventLoopGroup(1).next()
    "submit Callable"        | submitCallable      | new DefaultEventLoopGroup(1).next()
    "invokeAll"              | invokeAll           | new DefaultEventLoopGroup(1).next()
    "invokeAll with timeout" | invokeAllTimeout    | new DefaultEventLoopGroup(1).next()
    "invokeAny"              | invokeAny           | new DefaultEventLoopGroup(1).next()
    "invokeAny with timeout" | invokeAnyTimeout    | new DefaultEventLoopGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new DefaultEventLoopGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new DefaultEventLoopGroup(1).next()

    "execute Runnable"       | executeRunnable     | new NioEventLoopGroup(1).next()
    "submit Runnable"        | submitRunnable      | new NioEventLoopGroup(1).next()
    "submit Callable"        | submitCallable      | new NioEventLoopGroup(1).next()
    "invokeAll"              | invokeAll           | new NioEventLoopGroup(1).next()
    "invokeAll with timeout" | invokeAllTimeout    | new NioEventLoopGroup(1).next()
    "invokeAny"              | invokeAny           | new NioEventLoopGroup(1).next()
    "invokeAny with timeout" | invokeAnyTimeout    | new NioEventLoopGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new NioEventLoopGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new NioEventLoopGroup(1).next()

    "execute Runnable"       | executeRunnable     | epollExecutor()
    "submit Runnable"        | submitRunnable      | epollExecutor()
    "submit Callable"        | submitCallable      | epollExecutor()
    "invokeAll"              | invokeAll           | epollExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | epollExecutor()
    "invokeAny"              | invokeAny           | epollExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | epollExecutor()
    "schedule Runnable"      | scheduleRunnable    | epollExecutor()
    "schedule Callable"      | scheduleCallable    | epollExecutor()

    // ignore deprecation
    "execute Runnable"       | executeRunnable     | new LocalEventLoopGroup(1).next()
    "submit Runnable"        | submitRunnable      | new LocalEventLoopGroup(1).next()
    "submit Callable"        | submitCallable      | new LocalEventLoopGroup(1).next()
    "invokeAll"              | invokeAll           | new LocalEventLoopGroup(1).next()
    "invokeAll with timeout" | invokeAllTimeout    | new LocalEventLoopGroup(1).next()
    "invokeAny"              | invokeAny           | new LocalEventLoopGroup(1).next()
    "invokeAny with timeout" | invokeAnyTimeout    | new LocalEventLoopGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new LocalEventLoopGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new LocalEventLoopGroup(1).next()

    // TODO - UnorderedThreadPoolEventExecutor doesn't work yet
//    "execute Runnable"       | executeRunnable     | new UnorderedThreadPoolEventExecutor(1).next()
//    "submit Runnable"        | submitRunnable      | new UnorderedThreadPoolEventExecutor(1).next()
//    "submit Callable"        | submitCallable      | new UnorderedThreadPoolEventExecutor(1).next()
//    "invokeAll"              | invokeAll           | new UnorderedThreadPoolEventExecutor(1).next()
//    "invokeAll with timeout" | invokeAllTimeout    | new UnorderedThreadPoolEventExecutor(1).next()
//    "invokeAny"              | invokeAny           | new UnorderedThreadPoolEventExecutor(1).next()
//    "invokeAny with timeout" | invokeAnyTimeout    | new UnorderedThreadPoolEventExecutor(1).next()
//    "schedule Runnable"      | scheduleRunnable    | new UnorderedThreadPoolEventExecutor(1).next()
//    "schedule Callable"      | scheduleCallable    | new UnorderedThreadPoolEventExecutor(1).next()

  }

  def "#poolImpl '#name' reports after canceled jobs"() {
    setup:
    def pool = poolImpl
    def m = method
    List<JavaAsyncChild> children = new ArrayList<>()
    List<Future> jobFutures = new ArrayList<>()

    new Runnable() {
      @Override
      @Trace(operationName = "parent")
      void run() {
        activeScope().setAsyncPropagation(true)
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
        } catch (RejectedExecutionException e) {
        }

        for (Future f : jobFutures) {
          f.cancel(true)
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

    where:
    name                | method           | poolImpl
    "submit Runnable"   | submitRunnable   | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))
    "submit Callable"   | submitCallable   | new ThreadPoolExecutor(1, 1, 1000, TimeUnit.NANOSECONDS, new ArrayBlockingQueue<Runnable>(1))

    "submit Runnable"   | submitRunnable   | new DefaultEventExecutorGroup(1).next()
    "submit Callable"   | submitCallable   | new DefaultEventExecutorGroup(1).next()
    "schedule Runnable" | scheduleRunnable | new DefaultEventExecutorGroup(1).next()
    "schedule Callable" | scheduleCallable | new DefaultEventExecutorGroup(1).next()

    "submit Runnable"        | submitRunnable      | new DefaultEventLoopGroup(1).next()
    "submit Callable"        | submitCallable      | new DefaultEventLoopGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new DefaultEventLoopGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new DefaultEventLoopGroup(1).next()

    "submit Runnable"        | submitRunnable      | new NioEventLoopGroup(1).next()
    "submit Callable"        | submitCallable      | new NioEventLoopGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new NioEventLoopGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new NioEventLoopGroup(1).next()

    // ignore deprecation
    "submit Runnable"        | submitRunnable      | new LocalEventLoopGroup(1).next()
    "submit Callable"        | submitCallable      | new LocalEventLoopGroup(1).next()
    "schedule Runnable"      | scheduleRunnable    | new LocalEventLoopGroup(1).next()
    "schedule Callable"      | scheduleCallable    | new LocalEventLoopGroup(1).next()

    // TODO - UnorderedThreadPoolEventExecutor doesn't work yet
//    "submit Runnable"        | submitRunnable      | new UnorderedThreadPoolEventExecutor(1).next()
//    "submit Callable"        | submitCallable      | new UnorderedThreadPoolEventExecutor(1).next()
//    "schedule Runnable"      | scheduleRunnable    | new UnorderedThreadPoolEventExecutor(1).next()
//    "schedule Callable"      | scheduleCallable    | new UnorderedThreadPoolEventExecutor(1).next()
  }


  def epollExecutor() {
    // EPoll only works on linux
    isLinux ? new EpollEventLoopGroup(1).next() : null
  }
}
