package datadog.common.exec

import datadog.trace.util.gc.GCUtils
import datadog.trace.util.test.DDSpecification
import spock.lang.Retry

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static java.util.concurrent.TimeUnit.MILLISECONDS

@Retry
class PeriodicSchedulingTest extends DDSpecification {

  def "test scheduling"() {
    setup:
    def latch = new CountDownLatch(2)
    def task = new CommonTaskExecutor.Task<CountDownLatch>() {
      @Override
      void run(CountDownLatch target) {
        target.countDown()
      }
    }

    expect:
    !CommonTaskExecutor.INSTANCE.isShutdown()

    when:
    CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS, "test")

    then:
    latch.await(500, MILLISECONDS)
  }

  def "test canceling"() {
    setup:
    def callCount = new AtomicInteger()
    def target = new WeakReference(new Object())
    def task = new CommonTaskExecutor.Task<Object>() {
      @Override
      void run(Object t) {
        callCount.countDown()
      }
    }

    expect:
    !CommonTaskExecutor.INSTANCE.isShutdown()

    when:
    CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(task, target.get(), 10, 10, MILLISECONDS, "test")
    GCUtils.awaitGC(target)
    Thread.sleep(1)
    def snapshot = callCount.get()
    Thread.sleep(11)

    then:
    snapshot == callCount.get()
  }

  def "test null target"() {
    setup:
    def callCount = new AtomicInteger()
    def task = new CommonTaskExecutor.Task<Object>() {
      @Override
      void run(Object t) {
        callCount.countDown()
      }
    }

    expect:
    !CommonTaskExecutor.INSTANCE.isShutdown()

    when:
    CommonTaskExecutor.INSTANCE.scheduleAtFixedRate(task, null, 10, 10, MILLISECONDS, "test")
    Thread.sleep(11)

    then:
    callCount.get() == 0
  }
}
