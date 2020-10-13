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
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static org.junit.Assume.assumeTrue

class NettyExecutorInstrumentationTest extends AgentTestRunner {

  @Shared
  boolean isLinux = System.getProperty("os.name").toLowerCase().contains("linux")

  @Shared
  EpollEventLoopGroup epollEventLoopGroup = isLinux ? new EpollEventLoopGroup(4) : null
  @Shared
  UnorderedThreadPoolEventExecutor unorderedThreadPoolEventExecutor = new UnorderedThreadPoolEventExecutor(4)
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

  def "#poolImpl '#name' propagates"() {
    setup:
    assumeTrue(poolImpl != null) // skip for non-Linux Netty EPoll
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

    where:
    name                     | method              | poolImpl
    "execute Runnable"       | executeRunnable     | unorderedThreadPoolEventExecutor.next()
    "submit Runnable"        | submitRunnable      | unorderedThreadPoolEventExecutor.next()
    "submit Callable"        | submitCallable      | unorderedThreadPoolEventExecutor.next()
    "invokeAll"              | invokeAll           | unorderedThreadPoolEventExecutor.next()
    "invokeAll with timeout" | invokeAllTimeout    | unorderedThreadPoolEventExecutor.next()
    "invokeAny"              | invokeAny           | unorderedThreadPoolEventExecutor.next()
    "invokeAny with timeout" | invokeAnyTimeout    | unorderedThreadPoolEventExecutor.next()
    "schedule Runnable"      | scheduleRunnable    | unorderedThreadPoolEventExecutor.next()
    "schedule Callable"      | scheduleCallable    | unorderedThreadPoolEventExecutor.next()

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

    "execute Runnable"       | executeRunnable     | nioEventLoopGroup.next()
    "submit Runnable"        | submitRunnable      | nioEventLoopGroup.next()
    "submit Callable"        | submitCallable      | nioEventLoopGroup.next()
    "invokeAll"              | invokeAll           | nioEventLoopGroup.next()
    "invokeAll with timeout" | invokeAllTimeout    | nioEventLoopGroup.next()
    "invokeAny"              | invokeAny           | nioEventLoopGroup.next()
    "invokeAny with timeout" | invokeAnyTimeout    | nioEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | nioEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | nioEventLoopGroup.next()

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
    "execute Runnable"       | executeRunnable     | localEventLoopGroup.next()
    "submit Runnable"        | submitRunnable      | localEventLoopGroup.next()
    "submit Callable"        | submitCallable      | localEventLoopGroup.next()
    "invokeAll"              | invokeAll           | localEventLoopGroup.next()
    "invokeAll with timeout" | invokeAllTimeout    | localEventLoopGroup.next()
    "invokeAny"              | invokeAny           | localEventLoopGroup.next()
    "invokeAny with timeout" | invokeAnyTimeout    | localEventLoopGroup.next()
    "schedule Runnable"      | scheduleRunnable    | localEventLoopGroup.next()
    "schedule Callable"      | scheduleCallable    | localEventLoopGroup.next()

  }

  def "#poolImpl '#name' reports after canceled jobs"() {
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

    "submit Runnable"        | submitRunnable      | unorderedThreadPoolEventExecutor.next()
    "submit Callable"        | submitCallable      | unorderedThreadPoolEventExecutor.next()
    "schedule Runnable"      | scheduleRunnable    | unorderedThreadPoolEventExecutor.next()
    "schedule Callable"      | scheduleCallable    | unorderedThreadPoolEventExecutor.next()


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
  }

  def epollExecutor() {
    // EPoll only works on linux
    isLinux ? epollEventLoopGroup.next() : null
  }
}
