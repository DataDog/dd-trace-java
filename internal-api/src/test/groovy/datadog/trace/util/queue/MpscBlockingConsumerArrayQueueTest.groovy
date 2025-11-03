package datadog.trace.util.queue


import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Supplier
import spock.lang.Timeout

class MpscBlockingConsumerArrayQueueTest extends AbstractQueueTest<MpscBlockingConsumerArrayQueue<Integer>> {

  @Override
  MpscBlockingConsumerArrayQueue<Integer> createQueue(int capacity) {
    return new MpscBlockingConsumerArrayQueue(capacity)
  }

  def "put and take should block and release correctly"() {
    given:
    queue = new MpscBlockingConsumerArrayQueue<>(2)
    def taken = new AtomicReference<>()
    def latch = new CountDownLatch(1)

    when:
    Thread.start {
      taken.set(queue.take())
      latch.countDown()
    }

    Thread.sleep(100) // ensure consumer is waiting
    queue.put(42)
    latch.await(1, TimeUnit.SECONDS)

    then:
    taken.get() == 42
    queue.isEmpty()
  }

  def "put should block when full until space is available"() {
    given:
    queue = new MpscBlockingConsumerArrayQueue<>(2)
    queue.put(1)
    queue.put(2)
    def added = new AtomicBoolean(false)

    when:
    Thread producer = Thread.start {
      try {
        queue.put(3) // should block until consumer polls
        added.set(true)
      } catch (InterruptedException ignore) {
      }
    }

    Thread.sleep(100)
    assert !added.get()
    queue.take() // frees one slot
    producer.join(1000)

    then:
    added.get()
    queue.size() == 2
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
    queue = new MpscBlockingConsumerArrayQueue<>(1024)
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
    queue = new MpscBlockingConsumerArrayQueue<>(4)
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

  def "blocking put should wake up when consumer takes"() {
    given:
    queue = new MpscBlockingConsumerArrayQueue<>(1)
    queue.put(1)
    def done = new AtomicBoolean(false)

    when:
    Thread producer = Thread.start {
      try {
        queue.put(2) // blocks until consumer takes
        done.set(true)
      } catch (InterruptedException ignored) {
      }
    }

    Thread.sleep(100)
    queue.take()
    producer.join(1000)

    then:
    done.get()
    queue.size() == 1
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
}
