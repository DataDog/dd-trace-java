package datadog.common.queue


import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import spock.lang.Timeout

class MpscArrayQueueVarHandleTest extends AbstractQueueTest<MpscArrayQueueVarHandle> {

  @Timeout(10)
  def "multiple producers single consumer should consume all elements without duplication or loss"() {
    given:
    int total = 1000
    int producers = 4
    queue = new MpscArrayQueueVarHandle<>(1024)
    def results = Collections.synchronizedList([])
    def executor = Executors.newFixedThreadPool(producers)
    def latch = new CountDownLatch(producers)
    def consumerDone = new CountDownLatch(1)

    when: "multiple producers enqueue concurrently"
    (1..producers).each { id ->
      executor.submit {
        for (int i = 0; i < total / producers; i++) {
          int value = (id * 10000) + i
          while (!queue.offer(value)) {
            Thread.yield()
          }
        }
        latch.countDown()
      }
    }

    and: "a single consumer drains all elements"
    Thread consumer = new Thread({
      while (results.size() < total) {
        def v = queue.poll()
        if (v != null) {
          results << v
        } else {
          Thread.yield()
        }
      }
      consumerDone.countDown()
    })
    consumer.start()

    latch.await()
    consumerDone.await()
    executor.shutdown()

    then:
    results.size() == total
    results.toSet().size() == total // all unique
  }

  @Override
  MpscArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new MpscArrayQueueVarHandle<Integer>(capacity)
  }
}
