package datadog.common.queue


import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import spock.lang.Timeout

class SpmcArrayQueueVarHandleTest extends AbstractQueueTest<SpmcArrayQueueVarHandle<Integer>> {

  @Override
  SpmcArrayQueueVarHandle<Integer> createQueue(int capacity) {
    return new SpmcArrayQueueVarHandle<Integer>(capacity)
  }

  @Timeout(10)
  def "single producer multiple consumers should consume all elements without duplication or loss"() {
    given:
    int total = 1000
    int consumers = 4
    queue = new SpmcArrayQueueVarHandle<>(1024)
    def results = Collections.synchronizedList([])
    def executor = Executors.newFixedThreadPool(consumers)
    def latch = new CountDownLatch(consumers)

    when: "one producer fills the queue"
    Thread producer = new Thread({
      for (int i = 0; i < total; i++) {
        while (!queue.offer(i)) {
          Thread.yield()
        }
      }
    })
    producer.start()

    and: "multiple consumers drain concurrently"
    (1..consumers).each {
      executor.submit {
        while (results.size() < total) {
          def v = queue.poll()
          if (v != null) {
            results << v
          } else {
            Thread.yield()
          }
        }
        latch.countDown()
      }
    }

    latch.await()
    producer.join()
    executor.shutdown()

    then:
    results.size() == total
    results.toSet().size() == total // no duplicates
    results.containsAll((0..<total).toList()) // all items consumed
  }
}
