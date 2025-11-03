package datadog.trace.util.queue


import java.util.concurrent.atomic.AtomicInteger

class SpscArrayQueueTest extends AbstractQueueTest<SpscArrayQueue<Integer>> {

  def "single producer single consumer concurrency"() {
    given:
    def queue = new SpscArrayQueue<Integer>(1024)
    def producerCount = 1000
    def consumed = new AtomicInteger(0)
    def consumedValues = []

    def producer = Thread.start {
      (1..producerCount).each { queue.offer(it) }
    }

    def consumer = Thread.start {
      while (consumed.get() < producerCount) {
        def v = queue.poll()
        if (v != null) {
          consumedValues << v
          consumed.incrementAndGet()
        }
      }
    }

    when:
    producer.join()
    consumer.join()

    then:
    consumed.get() == producerCount
    consumedValues.toSet().size() == producerCount  // all values unique
  }

  @Override
  SpscArrayQueue<Integer> createQueue(int capacity) {
    return new SpscArrayQueue<Integer>(capacity)
  }
}
