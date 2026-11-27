import static org.junit.jupiter.api.Assumptions.assumeTrue

import datadog.trace.agent.test.InstrumentationSpecification
import io.netty.channel.EventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueEventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RegistrationForkedTest extends InstrumentationSpecification {

  private static final long TIMEOUT_SECONDS = 5

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    super.configurePreAgent()
  }

  def "test thread filter updates"() {
    setup:
    CyclicBarrier barrier = new CyclicBarrier(2)
    CountDownLatch latch = new CountDownLatch(2)
    ExecutorService executorService = Executors.newFixedThreadPool(2)

    when: "await cyclic barrier"
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    def f1 = executorService.submit {barrier.await()}
    def f2 = executorService.submit {barrier.await()}
    f1.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    f2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    then:
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 2
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 2

    when: "await countdown latch"
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    def f3 = executorService.submit {latch.await()}
    def f4 = executorService.submit {latch.await()}
    latch.countDown()
    latch.countDown()
    f3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    f4.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

    then:
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 2
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 2

    cleanup:
    executorService.shutdownNow()
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
  }

  def "test #transport event loop thread filter updates"() {
    setup:
    assumeTrue(isAvailable(), "${transport} is not available")
    def group = createGroup()
    assumeTrue(group != null, "${transport} is not on classpath")

    when:
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    group.submit({}).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
    boolean shutdownGracefully = shutdownGroup(group)

    then:
    shutdownGracefully
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 1
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 1

    cleanup:
    shutdownGroup(group)
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()

    where:
    transport                                         | isAvailable                                                       | createGroup
    "nio"                                             | { true }                                                          | { new NioEventLoopGroup(1) }
    "epoll"                                           | { Epoll.isAvailable() }                                           | { new EpollEventLoopGroup(1) }
    "kqueue"                                          | { KQueue.isAvailable() }                                          | { new KQueueEventLoopGroup(1) }
    "grpc shaded nio"                                 | { true }                                                          | { new io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup(1) }
    "grpc shaded epoll"                               | { io.grpc.netty.shaded.io.netty.channel.epoll.Epoll.isAvailable() } | { new io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup(1) }
    "Netty 4.2 MultiThreadIoEventLoopGroup (NIO)"     | { true }                                                          | { newNetty42IoEventLoopGroup("io.netty.channel.nio.NioIoHandler") }
    "Netty 4.2 MultiThreadIoEventLoopGroup (Epoll)"   | { Epoll.isAvailable() }                                           | { newNetty42IoEventLoopGroup("io.netty.channel.epoll.EpollIoHandler") }
    "Netty 4.2 MultiThreadIoEventLoopGroup (KQueue)"  | { KQueue.isAvailable() }                                          | { newNetty42IoEventLoopGroup("io.netty.channel.kqueue.KQueueIoHandler") }
  }

  private static boolean shutdownGroup(def group) {
    if (group == null) {
      return true
    }
    return group.shutdownGracefully(0, TIMEOUT_SECONDS, TimeUnit.SECONDS).await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }

  private static EventLoopGroup newNetty42IoEventLoopGroup(String ioHandlerClassName) {
    try {
      Class<?> groupClass = Class.forName("io.netty.channel.MultiThreadIoEventLoopGroup")
      Class<?> factoryClass = Class.forName("io.netty.channel.IoHandlerFactory")
      Class<?> handlerClass = Class.forName(ioHandlerClassName)
      Object factory = handlerClass.getMethod("newFactory").invoke(null)
      return (EventLoopGroup) groupClass.getConstructor(int.class, factoryClass).newInstance(1, factory)
    } catch (ClassNotFoundException ignored) {
      return null
    }
  }
}
