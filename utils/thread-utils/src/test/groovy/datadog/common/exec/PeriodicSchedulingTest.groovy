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
    def task = new AgentTaskScheduler.Task<CountDownLatch>() {
      @Override
      void run(CountDownLatch target) {
        target.countDown()
      }
    }

    expect:
    !AgentTaskScheduler.INSTANCE.isShutdown()

    when:
    AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS)

    then:
    latch.await(500, MILLISECONDS)
  }

  def "test canceling"() {
    setup:
    def callCount = new AtomicInteger()
    def target = new WeakReference(new Object())
    def task = new AgentTaskScheduler.Task<Object>() {
      @Override
      void run(Object t) {
        callCount.countDown()
      }
    }

    expect:
    !AgentTaskScheduler.INSTANCE.isShutdown()

    when:
    AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(task, target.get(), 10, 10, MILLISECONDS)
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
    def task = new AgentTaskScheduler.Task<Object>() {
      @Override
      void run(Object t) {
        callCount.countDown()
      }
    }

    expect:
    !AgentTaskScheduler.INSTANCE.isShutdown()

    when:
    AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(task, null, 10, 10, MILLISECONDS)
    Thread.sleep(11)

    then:
    callCount.get() == 0
  }
}
