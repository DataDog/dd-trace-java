package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The behaviour every backing must share, exercised against each of them. */
class QueueContractTest {

  private static final int CAPACITY = 4;

  static Stream<Arguments> boundedQueues() {
    return Stream.of(
        Arguments.of("mpsc", (IntFunction<Queue<String>>) Queues::mpscQueue),
        Arguments.of("mpmc", (IntFunction<Queue<String>>) Queues::mpmcQueue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void admitsUpToCapacityThenDrops(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    assertEquals(CAPACITY, queue.size());
    assertFalse(queue.tryPut("overflow"));
    assertEquals(CAPACITY, queue.size());
    assertEquals(1, queue.dropped());
  }

  /** The point of the whole API: a rejected element is never built. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void doesNotInvokeProducerWhenFull(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    AtomicBoolean produced = new AtomicBoolean();
    assertFalse(
        queue.tryPut(
            produced,
            flag -> {
              flag.set(true);
              return "built";
            }));
    assertFalse(produced.get(), "producer ran for an element that could not be admitted");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void invokesProducerWhenThereIsRoom(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    assertTrue(queue.tryPut("ctx", context -> context + "-built"));
    List<String> consumed = drain(queue);
    assertEquals(Arrays.asList("ctx-built"), consumed);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void batchAdmissionReportsRejectedElements(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    Collection<String> rejected = queue.tryPutBatch("a", "b", "c", "d", "e", "f");
    assertEquals(Arrays.asList("e", "f"), new ArrayList<>(rejected));
    assertEquals(2, queue.dropped());
  }

  /** A batch producer keeps what will not fit, so stopping early loses nothing. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void batchProducerRetainsUnpulledElements(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    Iterator<String> source = Arrays.asList("a", "b", "c", "d", "e", "f").iterator();
    BatchProducer<String> producer =
        new BatchProducer<String>() {
          @Override
          public boolean hasNext() {
            return source.hasNext();
          }

          @Override
          public String next() {
            return source.next();
          }
        };

    queue.put(producer);

    assertEquals(CAPACITY, queue.size());
    assertEquals(0, queue.dropped(), "stopping at capacity is not a drop");
    assertTrue(producer.hasNext(), "unpulled elements stay with the producer");
    assertEquals("e", producer.next());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processReportsWhetherThereWasWork(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    assertFalse(queue.process(item -> {}), "empty queue has no work");
    queue.tryPut("a");
    assertTrue(queue.process(item -> {}));
    assertFalse(queue.process(item -> {}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processReportsWorkEvenWhenConsumerThrows(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    assertTrue(
        queue.process(
            item -> {
              throw new IllegalStateException("boom");
            }),
        "the return value reports work found, not consumer success");
    assertEquals(1, queue.dropped(), "an unretried failure loses the item");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void retriesUntilTheStrategyGivesUp(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    AtomicInteger attempts = new AtomicInteger();
    List<Integer> reported = new ArrayList<>();

    RetryStrategy<String> strategy =
        (item, attempt, failure, retryQueue) -> {
          reported.add(attempt);
          return attempt < 2 && retryQueue.retry(item);
        };

    while (queue.process(
        item -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("boom");
        },
        strategy)) {
      // drain until the strategy stops resubmitting
    }

    assertEquals(2, attempts.get(), "consumed twice: original plus one retry");
    assertEquals(Arrays.asList(1, 2), reported, "attempt counts survive re-admission");
    assertEquals(1, queue.dropped(), "giving up loses the item");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void maxRetriesBoundsResubmission(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    AtomicInteger attempts = new AtomicInteger();
    RetryStrategy<String> strategy = new MaxRetries<>(3);

    while (queue.process(
        item -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("boom");
        },
        strategy)) {
      // drain
    }

    assertEquals(3, attempts.get());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void closeStopsAdmissionButKeepsBacklog(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    queue.close();

    assertTrue(queue.isClosed());
    assertFalse(queue.tryPut("b"));
    assertEquals(1, queue.size(), "already-admitted work survives so a consumer can finish");
    assertEquals(Arrays.asList("a"), drain(queue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void clearDiscardsContentsButLeavesAdmissionOpen(
      String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    queue.tryPutBatch("a", "b");
    queue.clear();

    assertEquals(0, queue.size());
    assertFalse(queue.isClosed());
    assertTrue(queue.tryPut("c"), "clear does not close");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void shutdownClosesAndDiscards(String name, IntFunction<Queue<String>> factory) {
    Queue<String> queue = factory.apply(CAPACITY);
    queue.tryPutBatch("a", "b");
    queue.shutdown();

    assertEquals(0, queue.size());
    assertTrue(queue.isClosed());
    assertFalse(queue.tryPut("c"));
  }

  @org.junit.jupiter.api.Test
  void unboundedQueueNeverRejects() {
    Queue<String> queue = Queues.unboundedMpmcQueue();
    for (int i = 0; i < 1000; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    assertEquals(1000, queue.size());
    assertEquals(0, queue.dropped());
  }

  @org.junit.jupiter.api.Test
  void unboundedQueueStillCloses() {
    Queue<String> queue = Queues.unboundedMpmcQueue();
    queue.close();
    assertFalse(queue.tryPut("a"));
  }

  private static List<String> drain(Queue<String> queue) {
    List<String> consumed = new ArrayList<>();
    while (queue.process(consumed::add)) {
      // drain
    }
    return consumed;
  }
}
