package executor

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.Trace
import datadog.trace.core.DDSpan
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.local.LocalEventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.util.concurrent.DefaultEventExecutorGroup
import runnable.JavaAsyncChild
import spock.lang.Shared

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

import static org.junit.jupiter.api.Assumptions.assumeTrue

class NettyExecutorInstrumentationTest extends InstrumentationSpecification {

  @Shared
  boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux")

  @Shared
  EpollEventLoopGroup epollEventLoopGroup = isLinux ? new EpollEventLoopGroup(4) : null
  @Shared
  DefaultEventExecutorGroup defaultEventExecutorGroup = new DefaultEventExecutorGroup(4)
  @Shared
  DefaultEventLoopGroup defaultEventLoopGroup = new DefaultEventLoopGroup(4)
  @Shared
  NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(4)
  @Shared
  LocalEventLoopGroup localEventLoopGroup = new LocalEventLoopGroup(4)

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

  def "#poolName '#name' propagates"() {
    setup:
    assumeTrue(poolImpl != null) // skip for non-Linux Netty EPoll
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

    where:
    name                     | method              | poolImpl
    // TODO flaky
    //    "execute Runnable"       | executeRunnable     | new UnorderedThreadPoolEventExecutor(1)
    //    "submit Runnable"        | submitRunnable      | new UnorderedThreadPoolEventExecutor(1)
    //    "submit Callable"        | submitCallable      | new UnorderedThreadPoolEventExecutor(1)
    //    "invokeAll"              | invokeAll           | new UnorderedThreadPoolEventExecutor(1)
    //    "invokeAll with timeout" | invokeAllTimeout    | new UnorderedThreadPoolEventExecutor(1)
    //    "invokeAny"              | invokeAny           | new UnorderedThreadPoolEventExecutor(1)
    //    "invokeAny with timeout" | invokeAnyTimeout    | new UnorderedThreadPoolEventExecutor(1)
    //    "schedule Runnable"      | scheduleRunnable    | new UnorderedThreadPoolEventExecutor(1)
    //    "schedule Callable"      | scheduleCallable    | new UnorderedThreadPoolEventExecutor(1)

    "execute Runnable"       | executeRunnable     | defaultEventExecutorGroup
    "submit Runnable"        | submitRunnable      | defaultEventExecutorGroup
    "submit Callable"        | submitCallable      | defaultEventExecutorGroup
    "invokeAll"              | invokeAll           | defaultEventExecutorGroup
    "invokeAll with timeout" | invokeAllTimeout    | defaultEventExecutorGroup
    "invokeAny"              | invokeAny           | defaultEventExecutorGroup
    "invokeAny with timeout" | invokeAnyTimeout    | defaultEventExecutorGroup
    "schedule Runnable"      | scheduleRunnable    | defaultEventExecutorGroup
    "schedule Callable"      | scheduleCallable    | defaultEventExecutorGroup

    "execute Runnable"       | executeRunnable     | defaultEventExecutorGroup.next()
    "submit Runnable"        | submitRunnable      | defaultEventExecutorGroup.next()
    "submit Callable"        | submitCallable      | defaultEventExecutorGroup.next()
    "invokeAll"              | invokeAll           | defaultEventExecutorGroup.next()
    "invokeAll with timeout" | invokeAllTimeout    | defaultEventExecutorGroup.next()
    "invokeAny"              | invokeAny           | defaultEventExecutorGroup.next()
    "invokeAny with timeout" | invokeAnyTimeout    | defaultEventExecutorGroup.next()
    "schedule Runnable"      | scheduleRunnable    | defaultEventExecutorGroup.next()
    "schedule Callable"      | scheduleCallable    | defaultEventExecutorGroup.next()

    "execute Runnable"       | executeRunnable     | defaultEventLoopGroup.next()
    "submit Runnable"        | submitRunnable      | defaultEventLoopGroup.next()
    "submit Callable"        | submitCallable      | defaultEventLoopGroup.next()
    "invokeAll"              | invokeAll           | defaultEventLoopGroup.next()
    "invokeAll with timeout" | invokeAllTimeout    | defaultEventLoopGroup.next()
    "invokeAny"              | invokeAny           | defaultEventLoopGroup.next()
    "invokeAny with timeout" | invokeAnyTimeout    | defaultEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | defaultEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | defaultEventLoopGroup.next()

    "execute Runnable"       | executeRunnable     | defaultEventLoopGroup
    "submit Runnable"        | submitRunnable      | defaultEventLoopGroup
    "submit Callable"        | submitCallable      | defaultEventLoopGroup
    "invokeAll"              | invokeAll           | defaultEventLoopGroup
    "invokeAll with timeout" | invokeAllTimeout    | defaultEventLoopGroup
    "invokeAny"              | invokeAny           | defaultEventLoopGroup
    "invokeAny with timeout" | invokeAnyTimeout    | defaultEventLoopGroup
    "schedule Runnable"      | scheduleRunnable    | defaultEventLoopGroup
    "schedule Callable"      | scheduleCallable    | defaultEventLoopGroup

    "execute Runnable"       | executeRunnable     | nioEventLoopGroup.next()
    "submit Runnable"        | submitRunnable      | nioEventLoopGroup.next()
    "submit Callable"        | submitCallable      | nioEventLoopGroup.next()
    "invokeAll"              | invokeAll           | nioEventLoopGroup.next()
    "invokeAll with timeout" | invokeAllTimeout    | nioEventLoopGroup.next()
    "invokeAny"              | invokeAny           | nioEventLoopGroup.next()
    "invokeAny with timeout" | invokeAnyTimeout    | nioEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | nioEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | nioEventLoopGroup.next()

    "execute Runnable"       | executeRunnable     | nioEventLoopGroup
    "submit Runnable"        | submitRunnable      | nioEventLoopGroup
    "submit Callable"        | submitCallable      | nioEventLoopGroup
    "invokeAll"              | invokeAll           | nioEventLoopGroup
    "invokeAll with timeout" | invokeAllTimeout    | nioEventLoopGroup
    "invokeAny"              | invokeAny           | nioEventLoopGroup
    "invokeAny with timeout" | invokeAnyTimeout    | nioEventLoopGroup
    "schedule Runnable"      | scheduleRunnable    | nioEventLoopGroup
    "schedule Callable"      | scheduleCallable    | nioEventLoopGroup

    "execute Runnable"       | executeRunnable     | epollExecutor()
    "submit Runnable"        | submitRunnable      | epollExecutor()
    "submit Callable"        | submitCallable      | epollExecutor()
    "invokeAll"              | invokeAll           | epollExecutor()
    "invokeAll with timeout" | invokeAllTimeout    | epollExecutor()
    "invokeAny"              | invokeAny           | epollExecutor()
    "invokeAny with timeout" | invokeAnyTimeout    | epollExecutor()
    "schedule Runnable"      | scheduleRunnable    | epollExecutor()
    "schedule Callable"      | scheduleCallable    | epollExecutor()

    "execute Runnable"       | executeRunnable     | epollEventLoopGroup
    "submit Runnable"        | submitRunnable      | epollEventLoopGroup
    "submit Callable"        | submitCallable      | epollEventLoopGroup
    "invokeAll"              | invokeAll           | epollEventLoopGroup
    "invokeAll with timeout" | invokeAllTimeout    | epollEventLoopGroup
    "invokeAny"              | invokeAny           | epollEventLoopGroup
    "invokeAny with timeout" | invokeAnyTimeout    | epollEventLoopGroup
    "schedule Runnable"      | scheduleRunnable    | epollEventLoopGroup
    "schedule Callable"      | scheduleCallable    | epollEventLoopGroup

    // ignore deprecation
    "execute Runnable"       | executeRunnable     | localEventLoopGroup.next()
    "submit Runnable"        | submitRunnable      | localEventLoopGroup.next()
    "submit Callable"        | submitCallable      | localEventLoopGroup.next()
    "invokeAll"              | invokeAll           | localEventLoopGroup.next()
    "invokeAll with timeout" | invokeAllTimeout    | localEventLoopGroup.next()
    "invokeAny"              | invokeAny           | localEventLoopGroup.next()
    "invokeAny with timeout" | invokeAnyTimeout    | localEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | localEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | localEventLoopGroup.next()

    "execute Runnable"       | executeRunnable     | localEventLoopGroup
    "submit Runnable"        | submitRunnable      | localEventLoopGroup
    "submit Callable"        | submitCallable      | localEventLoopGroup
    "invokeAll"              | invokeAll           | localEventLoopGroup
    "invokeAll with timeout" | invokeAllTimeout    | localEventLoopGroup
    "invokeAny"              | invokeAny           | localEventLoopGroup
    "invokeAny with timeout" | invokeAnyTimeout    | localEventLoopGroup
    "schedule Runnable"      | scheduleRunnable    | localEventLoopGroup
    "schedule Callable"      | scheduleCallable    | localEventLoopGroup

    poolName = poolImpl?.class?.simpleName
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

    where:
    name                     | method              | poolImpl
    // TODO flaky
    //    "submit Runnable"        | submitRunnable      | new UnorderedThreadPoolEventExecutor(1)
    //    "submit Callable"        | submitCallable      | new UnorderedThreadPoolEventExecutor(1)
    //    "schedule Runnable"      | scheduleRunnable    | new UnorderedThreadPoolEventExecutor(1)
    //    "schedule Callable"      | scheduleCallable    | new UnorderedThreadPoolEventExecutor(1)

    "submit Runnable"        | submitRunnable      | defaultEventExecutorGroup.next()
    "submit Callable"        | submitCallable      | defaultEventExecutorGroup.next()
    "schedule Runnable"      | scheduleRunnable    | defaultEventExecutorGroup.next()
    "schedule Callable"      | scheduleCallable    | defaultEventExecutorGroup.next()

    "submit Runnable"        | submitRunnable      | defaultEventLoopGroup.next()
    "submit Callable"        | submitCallable      | defaultEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | defaultEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | defaultEventLoopGroup.next()

    "submit Runnable"        | submitRunnable      | nioEventLoopGroup.next()
    "submit Callable"        | submitCallable      | nioEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | nioEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | nioEventLoopGroup.next()

    "submit Runnable"        | submitRunnable      | epollExecutor()
    "submit Callable"        | submitCallable      | epollExecutor()
    "schedule Runnable"      | scheduleRunnable    | epollExecutor()
    "schedule Callable"      | scheduleCallable    | epollExecutor()

    // ignore deprecation
    "submit Runnable"        | submitRunnable      | localEventLoopGroup.next()
    "submit Callable"        | submitCallable      | localEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | localEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | localEventLoopGroup.next()

    poolName = poolImpl?.class?.simpleName
  }

  def epollExecutor() {
    // EPoll only works on linux
    isLinux ? epollEventLoopGroup.next() : null
  }
}
