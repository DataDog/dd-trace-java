package datadog.trace.util.queue


import datadog.trace.test.util.DDSpecification
import java.util.function.Consumer

abstract class AbstractQueueTest<T extends BaseQueue<Integer>> extends DDSpecification {
  abstract T createQueue(int capacity)
  protected T queue = createQueue(8)

  def "offer and poll should preserve FIFO order"() {
    when:
    queue.offer(1)
    queue.offer(2)
    queue.offer(3)

    then:
    queue.poll() == 1
    queue.poll() == 2
    queue.poll() == 3
    queue.poll() == null
  }

  def "offer should return false when queue is full"() {
    given:
    queue.clear()
    (1..8).each { queue.offer(it) }

    expect:
    !queue.offer(999)
    queue.size() == 8
  }

  def "peek should return head element without removing it"() {
    given:
    queue.clear()
    queue.offer(10)
    queue.offer(20)

    expect:
    queue.peek() == 10
    queue.peek() == 10
    queue.size() == 2
  }

  def "poll should return null when empty"() {
    given:
    queue.clear()

    expect:
    queue.poll() == null
  }

  def "size should reflect current number of items"() {
    when:
    queue.clear()
    queue.offer(1)
    queue.offer(2)

    then:
    queue.size() == 2

    when:
    queue.poll()
    queue.poll()

    then:
    queue.size() == 0
  }

  def "drain should consume all available elements"() {
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

  def "drain with limit should only consume that many elements"() {
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

  def "remainingCapacity should reflect current occupancy"() {
    given:
    def q = createQueue(4)
    q.offer(1)
    q.offer(2)

    expect:
    q.remainingCapacity() == 2

    when:
    q.poll()

    then:
    q.remainingCapacity() == 3
  }
}
