import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan
import io.netty.channel.DefaultEventLoopGroup
import io.netty.channel.ThreadPerChannelEventLoop
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.oio.OioEventLoopGroup
import io.netty.util.concurrent.DefaultEventExecutor
import org.apache.tomcat.util.threads.TaskQueue
import runnable.Descendant
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CrossedContextTest extends InstrumentationSpecification {

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
          fn(sut, new Descendant(threadName))
        }
      })
    }

    TEST_WRITER.waitForTraces(taskCount)
    then:
    for (List<DDSpan> trace : TEST_WRITER) {
      assert trace.size() == 2
      DDSpan parent = trace.find({ it.checkRootSpan() })
      assert null != parent
      DDSpan child = trace.find({ it.getParentId() == parent.getSpanId() })
      assert null != child
      assert child.getOperationName().toString().startsWith(parent.getOperationName().toString())
    }

    cleanup:
    executor.shutdownNow()

    where:
    executor                                                                                          | action       | function
    new ForkJoinPool()                                                                                | "submission" | submitRunnable
    new ForkJoinPool(10)                                                                              | "submission" | submitRunnable
    Executors.newSingleThreadExecutor()                                                               | "submission" | submitRunnable
    Executors.newSingleThreadScheduledExecutor()                                                      | "submission" | submitRunnable
    Executors.newFixedThreadPool(10)                                                                  | "submission" | submitRunnable
    Executors.newScheduledThreadPool(10)                                                              | "submission" | submitRunnable
    Executors.newCachedThreadPool()                                                                   | "submission" | submitRunnable
    new DefaultEventLoopGroup(10)                                                                     | "submission" | submitRunnable
    new DefaultEventLoopGroup(1).next()                                                               | "submission" | submitRunnable
    // TODO - flaky - seems to be relying on PendingTrace flush
    // new UnorderedThreadPoolEventExecutor(10)                                                          | "submission" | submitRunnable
    new NioEventLoopGroup(10)                                                                         | "submission" | submitRunnable
    new DefaultEventExecutor()                                                                        | "submission" | submitRunnable
    new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue()) | "submission" | submitRunnable
    new ForkJoinPool()                                                                                | "execution"  | executeRunnable
    new ForkJoinPool(10)                                                                              | "execution"  | executeRunnable
    Executors.newSingleThreadExecutor()                                                               | "execution"  | executeRunnable
    Executors.newSingleThreadScheduledExecutor()                                                      | "execution"  | executeRunnable
    Executors.newFixedThreadPool(10)                                                                  | "execution"  | executeRunnable
    Executors.newScheduledThreadPool(10)                                                              | "execution"  | executeRunnable
    Executors.newCachedThreadPool()                                                                   | "execution"  | executeRunnable
    new DefaultEventLoopGroup(10)                                                                     | "execution"  | executeRunnable
    new DefaultEventLoopGroup(1).next()                                                               | "execution"  | executeRunnable
    // TODO - flaky - seems to be relying on PendingTrace flush
    // new UnorderedThreadPoolEventExecutor(10)                                                          | "execution"  | executeRunnable
    new NioEventLoopGroup(10)                                                                         | "execution"  | executeRunnable
    new DefaultEventExecutor()                                                                        | "execution"  | executeRunnable
    new org.apache.tomcat.util.threads.ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new TaskQueue()) | "execution"  | executeRunnable
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
          pool.execute(new Descendant(threadName))
        }
      })
    }

    TEST_WRITER.waitForTraces(taskCount)
    then:
    for (List<DDSpan> trace : TEST_WRITER) {
      assert trace.size() == 2
      DDSpan parent = trace.find({ it.checkRootSpan() })
      assert null != parent
      DDSpan child = trace.find({ it.getParentId() == parent.getSpanId() })
      assert null != child
      assert child.getOperationName().toString().startsWith(parent.getOperationName().toString())
    }

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
