package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class AbstractQueueTest<T extends BaseQueue<Integer>> {
  abstract T createQueue(int capacity);

  protected T queue;

  @BeforeEach
  void setUp() {
    queue = createQueue(8);
  }

  @Test
  void offerAndPollShouldPreserveFIFOOrder() {
    queue.offer(1);
    queue.offer(2);
    queue.offer(3);

    assertEquals(1, queue.poll());
    assertEquals(2, queue.poll());
    assertEquals(3, queue.poll());
    assertNull(queue.poll());
  }

  @Test
  void offerShouldReturnFalseWhenQueueIsFull() {
    queue.clear();
    for (int i = 1; i <= 8; i++) {
      queue.offer(i);
    }

    assertFalse(queue.offer(999));
    assertEquals(8, queue.size());
  }

  @Test
  void peekShouldReturnHeadElementWithoutRemovingIt() {
    queue.clear();
    queue.offer(10);
    queue.offer(20);

    assertEquals(10, queue.peek());
    assertEquals(10, queue.peek());
    assertEquals(2, queue.size());
  }

  @Test
  void pollShouldReturnNullWhenEmpty() {
    queue.clear();

    assertNull(queue.poll());
  }

  @Test
  void sizeShouldReflectCurrentNumberOfItems() {
    queue.clear();
    queue.offer(1);
    queue.offer(2);

    assertEquals(2, queue.size());

    queue.poll();
    queue.poll();

    assertEquals(0, queue.size());
  }

  @Test
  void drainShouldConsumeAllAvailableElements() {
    queue.clear();
    for (int i = 1; i <= 5; i++) {
      queue.offer(i);
    }
    List<Integer> drained = new ArrayList<>();

    int count = queue.drain(drained::add);

    assertEquals(5, count);
    assertEquals(List.of(1, 2, 3, 4, 5), drained);
    assertTrue(queue.isEmpty());
  }

  @Test
  void drainWithLimitShouldOnlyConsumeThatManyElements() {
    queue.clear();
    for (int i = 1; i <= 6; i++) {
      queue.offer(i);
    }
    List<Integer> drained = new ArrayList<>();

    int count = queue.drain(drained::add, 3);

    assertEquals(3, count);
    assertEquals(List.of(1, 2, 3), drained);
    assertEquals(3, queue.size());
  }

  @Test
  void remainingCapacityShouldReflectCurrentOccupancy() {
    T q = createQueue(4);
    q.offer(1);
    q.offer(2);

    assertEquals(2, q.capacity() - q.size());

    q.poll();

    assertEquals(3, q.capacity() - q.size());
  }
}
