package datadog.trace.util

import datadog.trace.test.util.DDSpecification
import datadog.trace.test.util.GCUtils

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.NANOSECONDS
import static java.util.concurrent.TimeUnit.SECONDS

class AgentTaskSchedulerTest extends DDSpecification {

  AgentTaskScheduler scheduler

  def setup() {
    scheduler = new AgentTaskScheduler(TASK_SCHEDULER)
  }

  def cleanup() {
    scheduler.shutdown(10, MILLISECONDS)
  }

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
    !scheduler.isShutdown()

    when:
    scheduler.scheduleAtFixedRate(task, latch, 50, 10, MILLISECONDS)
    scheduler.taskCount() == 1

    then:
    latch.await(500, MILLISECONDS)
  }

  //@Flaky("awaitGC is flaky")
  def "test weak scheduling"() {
    setup:
    def latch = new CountDownLatch(Integer.MAX_VALUE)
    def weakLatch = new WeakReference(latch)
    def task = new AgentTaskScheduler.Task<CountDownLatch>() {
        @Override
        void run(CountDownLatch target) {
          target.countDown()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    scheduler.weakScheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS)
    scheduler.taskCount() == 1
    latch = null

    then:
    GCUtils.awaitGC(weakLatch)
    sleep(100)
    scheduler.taskCount() == 0
  }

  def "test delay"() {
    setup:
    def latch = new CountDownLatch(1)
    def task = new AgentTaskScheduler.Task<CountDownLatch>() {
        @Override
        void run(CountDownLatch target) {
          target.countDown()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    scheduler.schedule(task, latch, 10, MILLISECONDS)
    scheduler.taskCount() == 1

    then:
    latch.await(500, MILLISECONDS)
    scheduler.taskCount() == 0
  }

  def delta(long l1, long l2, float ratio, long expected) {
    def d = l1 - l2
    def interval = (d * ratio).longValue()
    (d >= expected - interval) && (d <= expected + interval)
  }

  def "test fixed delay"() {
    setup:
    def latch = new CountDownLatch(3)
    def timestamps = new ArrayList<Long>()
    def task = new AgentTaskScheduler.Task<CountDownLatch>() {
        @Override
        void run(CountDownLatch target) {
          timestamps.add(System.nanoTime())
          Thread.sleep(100)
          target.countDown()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    scheduler.scheduleWithFixedDelay(task, latch, 0, 200, MILLISECONDS)
    scheduler.taskCount() == 1

    then:
    latch.await(1000, MILLISECONDS)
    delta(timestamps.get(1), timestamps.get(0), 0.15, NANOSECONDS.convert(300, MILLISECONDS))
    delta(timestamps.get(2), timestamps.get(1), 0.15, NANOSECONDS.convert(300, MILLISECONDS))
  }

  def "test cancel"() {
    setup:
    def latch = new CountDownLatch(Integer.MAX_VALUE)
    def task = new AgentTaskScheduler.Task<CountDownLatch>() {
        @Override
        void run(CountDownLatch target) {
          target.countDown()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    def scheduled = scheduler.scheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS)
    scheduler.taskCount() == 1

    then:
    scheduled.cancel()
    sleep(100)
    scheduler.taskCount() == 0
  }

  def "test execute"() {
    setup:
    def latch = new CountDownLatch(1)
    def target = new Runnable() {
        @Override
        void run() {
          latch.countDown()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    scheduler.execute(target)
    scheduler.taskCount() == 1

    then:
    latch.await(500, MILLISECONDS)
    scheduler.taskCount() == 0
  }

  def "test shutdown"() {
    setup:
    def latch = new CountDownLatch(Integer.MAX_VALUE)
    def task = new AgentTaskScheduler.Task<CountDownLatch>() {
        @Override
        void run(CountDownLatch target) {
          target.countDown()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    scheduler.scheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS)
    scheduler.taskCount() == 1

    then:
    scheduler.shutdown(1, SECONDS)
    scheduler.isShutdown()
    scheduler.taskCount() == 0
  }

  def "test null target"() {
    setup:
    def callCount = new AtomicInteger()
    def task = new AgentTaskScheduler.Task<Object>() {
        @Override
        void run(Object t) {
          callCount.incrementAndGet()
        }
      }

    expect:
    !scheduler.isShutdown()

    when:
    scheduler.scheduleAtFixedRate(task, null, 10, 10, MILLISECONDS)

    then:
    scheduler.taskCount() == 0
    callCount.get() == 0
  }
}
