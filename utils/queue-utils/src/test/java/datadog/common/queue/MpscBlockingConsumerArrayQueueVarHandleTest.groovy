package datadog.common.queue

import static java.util.concurrent.TimeUnit.NANOSECONDS

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Supplier
import spock.lang.Timeout

class MpscBlockingConsumerArrayQueueVarHandleTest extends AbstractQueueTest<MpscBlockingConsumerArrayQueueVarHandle<Integer>> {

  @Override
  MpscBlockingConsumerArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new MpscBlockingConsumerArrayQueueVarHandle(capacity)
  }

  def "drain should consume all elements in order"() {
    given:
    queue.clear()
    (1..5).each { queue.offer(it) }
    def drained = []

    when:
    def count = queue.drain({ drained << it } as Consumer)

    then:
    count == 5
    drained == [1, 2, 3, 4, 5]
    queue.isEmpty()
  }

  def "drain with limit should consume only limited number"() {
    given:
    queue.clear()
    (1..6).each { queue.offer(it) }
    def drained = []

    when:
    def count = queue.drain({ drained << it } as Consumer, 3)

    then:
    count == 3
    drained == [1, 2, 3]
    queue.size() == 3
  }

  @Timeout(10)
  def "multiple producers single consumer should consume all elements without duplicates"() {
    given:
    int total = 1000
    int producers = 4
    queue = new MpscBlockingConsumerArrayQueueVarHandle<>(1024)
    def results = Collections.synchronizedList([])
    def latch = new CountDownLatch(producers)

    when:
    // Multiple producers
    (1..producers).each { id ->
      Thread.start {
        for (int i = 0; i < total / producers; i++) {
          int val = id * 10_000 + i
          while (!queue.offer(val)) {
            Thread.yield()
          }
        }
        latch.countDown()
      }
    }

    // Single consumer
    Thread consumer = Thread.start {
      while (results.size() < total) {
        def v = queue.poll()
        if (v != null) {
          results << v
        }
        else {
          Thread.yield()
        }
      }
    }

    latch.await()
    consumer.join()

    then:
    results.size() == total
    results.toSet().size() == total // all unique
  }

  def "blocking take should wake up when producer offers"() {
    given:
    queue = new MpscBlockingConsumerArrayQueueVarHandle<>(4)
    def result = new AtomicReference<>()

    when:
    Thread consumer = Thread.start {
      try {
        result.set(queue.take())
      } catch (InterruptedException ignored) {
      }
    }
    Thread.sleep(100)
    queue.offer(123)
    consumer.join(1000)

    then:
    result.get() == 123
    queue.isEmpty()
  }

  def "fill inserts up to capacity"() {
    given:
    def counter = 0
    def supplier = { counter < 10 ? counter++ : null } as Supplier<Integer>

    when:
    def filled = queue.fill(supplier, 10)

    then:
    filled == 8
    queue.size() == 8
  }

  def "poll with timeout returns null if no element becomes available"() {
    when:
    def start = System.nanoTime()
    def value = queue.poll(200, TimeUnit.MILLISECONDS)
    def elapsedMs = NANOSECONDS.toMillis(System.nanoTime() - start)

    then:
    value == null
    elapsedMs >= 200  // waited approximately the timeout
  }

  def "poll with zero timeout behaves like immediate poll"() {
    expect:
    queue.poll(0, TimeUnit.MILLISECONDS) == null

    when:
    queue.offer(99)

    then:
    queue.poll(0, TimeUnit.MILLISECONDS) == 99
  }

  def "poll throws InterruptedException if interrupted"() {
    given:
    def thrown = new AtomicBoolean()
    def thread = Thread.start {
      try {
        queue.poll(500, TimeUnit.MILLISECONDS)
      } catch (InterruptedException ie) {
        thrown.set(true)
        Thread.currentThread().interrupt()
      }
    }

    when:
    Thread.sleep(50)
    thread.interrupt()
    thread.join()

    then:
    thrown.get()
  }
}
