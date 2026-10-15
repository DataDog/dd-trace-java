import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.ThreadPerChannelEventLoop
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.oio.OioEventLoopGroup
import io.netty.util.concurrent.DefaultEventExecutor
import executor.NettyDescendant
import static executor.NettyTestExecutors.shutdown
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class NettyCrossedContextTest extends InstrumentationSpecification {

  @Shared
  def executeRunnable = { e, c -> e.execute((Runnable) c) }
  @Shared
  def submitRunnable = { e, c -> e.submit((Runnable) c) }

  // this pool is not what's being tested
  @Shared
  ExecutorService pool = Executors.newFixedThreadPool(40, new ThreadFactory() {
    AtomicInteger i = new AtomicInteger()

    @Override
    Thread newThread(Runnable r) {
      Thread t = new Thread(r)
      t.setName("parent-" + i.getAndIncrement())
      return t
    }
  })

  def cleanupSpec() {
    pool.shutdownNow()
  }

  def "concurrent #action are traced with correct lineage in #executor.class.name"() {
    when:
    def sut = executor
    def fn = function
    int taskCount = 200
    for (int i = 0; i < taskCount; ++i) {
      pool.execute({
        String threadName = Thread.currentThread().getName()
        runUnderTrace(threadName) {
          fn(sut, new NettyDescendant(threadName))
        }
      })
    }

    TEST_WRITER.waitForTraces(taskCount)
    then:
    for (List<DDSpan> trace : TEST_WRITER) {
      assert trace.size() == 2
      DDSpan parent = trace.find({ it.isRootSpan() })
      assert null != parent
      DDSpan child = trace.find({ it.getParentId() == parent.getSpanId() })
      assert null != child
      assert child.getOperationName().toString().startsWith(parent.getOperationName().toString())
    }

    cleanup:
    shutdown(executor)

    where:
    executor                                                                                          | action       | function
    new DefaultEventLoopGroup(10)                                                                     | "submission" | submitRunnable
    new DefaultEventLoopGroup(1).next()                                                               | "submission" | submitRunnable
    // TODO - flaky - seems to be relying on PendingTrace flush
    // new UnorderedThreadPoolEventExecutor(10)                                                          | "submission" | submitRunnable
    new NioEventLoopGroup(10)                                                                         | "submission" | submitRunnable
    new DefaultEventExecutor()                                                                        | "submission" | submitRunnable
    new DefaultEventLoopGroup(10)                                                                     | "execution"  | executeRunnable
    new DefaultEventLoopGroup(1).next()                                                               | "execution"  | executeRunnable
    // TODO - flaky - seems to be relying on PendingTrace flush
    // new UnorderedThreadPoolEventExecutor(10)                                                          | "execution"  | executeRunnable
    new NioEventLoopGroup(10)                                                                         | "execution"  | executeRunnable
    new DefaultEventExecutor()                                                                        | "execution"  | executeRunnable
  }

  def "netty event loop internal executions in #executor.class.name are traced with correct lineage" () {
    setup:
    ExecutorService pool = executor
    when:

    int taskCount = 200
    for (int i = 0; i < taskCount; ++i) {
      pool.execute({
        String threadName = Thread.currentThread().getName()
        runUnderTrace(threadName) {
          pool.execute(new NettyDescendant(threadName))
        }
      })
    }

    TEST_WRITER.waitForTraces(taskCount)
    then:
    for (List<DDSpan> trace : TEST_WRITER) {
      assert trace.size() == 2
      DDSpan parent = trace.find({ it.isRootSpan() })
      assert null != parent
      DDSpan child = trace.find({ it.getParentId() == parent.getSpanId() })
      assert null != child
      assert child.getOperationName().toString().startsWith(parent.getOperationName().toString())
    }

    cleanup:
    shutdown(executor)

    where:
    executor << [
      new ThreadPerChannelEventLoop(new OioEventLoopGroup()),
      new DefaultEventExecutor(),
      new NioEventLoopGroup(10),
      new DefaultEventLoopGroup(10)
      // flaky
      // new UnorderedThreadPoolEventExecutor(10)
    ]
  }
}
