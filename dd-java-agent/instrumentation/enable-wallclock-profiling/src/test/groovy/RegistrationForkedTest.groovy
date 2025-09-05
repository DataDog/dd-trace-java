import datadog.trace.agent.test.InstrumentationSpecification
import io.netty.channel.nio.NioEventLoopGroup

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RegistrationForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    super.configurePreAgent()
  }

  def "test thread filter updates"() {
    setup:
    NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup(1)
    CyclicBarrier barrier = new CyclicBarrier(2)
    CountDownLatch latch = new CountDownLatch(2)
    ExecutorService executorService = Executors.newFixedThreadPool(2)

    when: "run nio event loop"
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    nioEventLoopGroup.execute {}
    boolean shutdownGracefully = nioEventLoopGroup.shutdownGracefully().await(5, TimeUnit.SECONDS)

    then:
    shutdownGracefully
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 1
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 1

    when: "await cyclic barrier"
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    def f1 = executorService.submit {barrier.await()}
    def f2 = executorService.submit {barrier.await()}
    f1.get(5, TimeUnit.SECONDS)
    f2.get(5, TimeUnit.SECONDS)

    then:
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 2
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 2

    when: "await countdown latch"
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
    def f3 = executorService.submit {latch.await()}
    def f4 = executorService.submit {latch.await()}
    latch.countDown()
    latch.countDown()
    f3.get(5, TimeUnit.SECONDS)
    f4.get(5, TimeUnit.SECONDS)

    then:
    TEST_PROFILING_CONTEXT_INTEGRATION.attachments.get() == 2
    TEST_PROFILING_CONTEXT_INTEGRATION.detachments.get() == 2

    cleanup:
    executorService.shutdownNow()
    TEST_PROFILING_CONTEXT_INTEGRATION.clear()
  }
}
