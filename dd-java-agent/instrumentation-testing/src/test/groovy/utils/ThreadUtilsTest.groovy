package utils

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.test.util.ThreadUtils
import org.spockframework.runtime.ConditionNotSatisfiedError
import spock.lang.FailsWith
import spock.lang.Shared

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ThreadUtilsTest extends InstrumentationSpecification {

  @Shared
  def counter = new AtomicInteger()

  @Shared
  def byThread = new ConcurrentHashMap<String, Integer>()

  def cleanup() {
    counter.set(0)
  }

  def "run a closure concurrently"() {
    expect:
    ThreadUtils.runConcurrently(10, 200, {
      def total = counter.incrementAndGet()
      def name = Thread.currentThread().getName()
      def mine = byThread.get(name, 0) + 1
      byThread.put(name, mine)
      assert total >= mine
    })
    assert counter.get() == 200
    assert byThread.size() == 10
    byThread.each {
      assert it.value == 20
    }
  }

  @FailsWith(value = ConditionNotSatisfiedError)
  def "assert should fail test"() {
    expect:
    ThreadUtils.runConcurrently(10, 200, {
      def total = counter.incrementAndGet()
      assert total <= 17
    })
    // we should never get here
    assert "we should" == "never get here"
  }

  @FailsWith(value = ConditionNotSatisfiedError)
  def "assert as last operation should fail test"() {
    expect:
    ThreadUtils.runConcurrently(10, 200, {
      def total = counter.incrementAndGet()
      if (total == 200) {
        Thread.sleep(500)
      }
      assert total < 200
    })
    // we should never get here
    assert "we should" == "never get here"
  }

  def "concurrency 1 should run on this thread"() {
    when:
    def thread = Thread.currentThread()

    then:
    ThreadUtils.runConcurrently(1, 200, {
      counter.incrementAndGet()
      assert Thread.currentThread() == thread
    })
    assert counter.get() == 200
  }

  def "totalIterations 1 should run on this thread"() {
    when:
    def thread = Thread.currentThread()

    then:
    ThreadUtils.runConcurrently(10, 1, {
      counter.incrementAndGet()
      assert Thread.currentThread() == thread
    })
    assert counter.get() == 1
  }
}
