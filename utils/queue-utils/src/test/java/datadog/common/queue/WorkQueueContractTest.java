package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The behaviour every backing must share, exercised against each of them. */
class WorkQueueContractTest {

  private static final int CAPACITY = 4;

  static Stream<Arguments> boundedQueues() {
    return Stream.of(
        Arguments.of("mpsc", (IntFunction<WorkQueue<String>>) WorkQueues::createMpscQueue),
        Arguments.of("mpmc", (IntFunction<WorkQueue<String>>) WorkQueues::createMpmcQueue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void admitsUpToCapacityThenDrops(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
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
  void doesNotInvokeProducerWhenFull(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
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
  void invokesProducerWhenThereIsRoom(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertTrue(queue.tryPut("ctx", context -> context + "-built"));
    List<String> consumed = drain(queue);
    assertEquals(Arrays.asList("ctx-built"), consumed);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void batchAdmissionReportsRejectedElements(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    Collection<String> rejected = queue.tryPutBatch("a", "b", "c", "d", "e", "f");
    assertEquals(Arrays.asList("e", "f"), new ArrayList<>(rejected));
    assertEquals(2, queue.dropped());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processReportsWhetherThereWasWork(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertFalse(queue.process(item -> {}), "empty queue has no work");
    queue.tryPut("a");
    assertTrue(queue.process(item -> {}));
    assertFalse(queue.process(item -> {}));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processPropagatesAConsumerFailureWhenGivenNoStrategy(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                queue.process(
                    item -> {
                      throw new IllegalStateException("boom");
                    }),
            "without a strategy the queue takes no view on failure");

    assertEquals("boom", thrown.getMessage());
    assertEquals(0, queue.dropped(), "a failure the caller sees is not a silent drop");
    assertEquals(0, queue.size(), "the item was still consumed off the queue");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processReportsWorkEvenWhenTheStrategyGivesUp(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    RetryStrategy<String> giveUp = (item, attempt, failure, retryQueue) -> false;
    assertTrue(
        queue.process(
            item -> {
              throw new IllegalStateException("boom");
            },
            giveUp),
        "the return value reports work found, not consumer success");
    assertEquals(1, queue.dropped(), "an abandoned item is counted");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void retriesUntilTheStrategyGivesUp(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
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
  void maxRetriesBoundsResubmission(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
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
  void closeStopsAdmissionButKeepsBacklog(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
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
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPutBatch("a", "b");
    queue.clear();

    assertEquals(0, queue.size());
    assertFalse(queue.isClosed());
    assertTrue(queue.tryPut("c"), "clear does not close");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void shutdownClosesAndDiscards(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPutBatch("a", "b");
    queue.shutdown();

    assertEquals(0, queue.size());
    assertTrue(queue.isClosed());
    assertFalse(queue.tryPut("c"));
  }

  @org.junit.jupiter.api.Test
  void unboundedQueueNeverRejects() {
    WorkQueue<String> queue = WorkQueues.createUnboundedMpmcQueue();
    for (int i = 0; i < 1000; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    assertEquals(1000, queue.size());
    assertEquals(0, queue.dropped());
  }

  @org.junit.jupiter.api.Test
  void unboundedQueueStillCloses() {
    WorkQueue<String> queue = WorkQueues.createUnboundedMpmcQueue();
    queue.close();
    assertFalse(queue.tryPut("a"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void retryCanPartitionFailedWorkIntoSeveralItems(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("ab");
    List<String> consumed = new ArrayList<>();
    RetryStrategy<String> split =
        (item, attempt, failure, retryQueue) -> retryQueue.retry("a", "b");

    while (queue.process(
        item -> {
          if (item.length() > 1) {
            throw new IllegalStateException("too big to handle in one piece");
          }
          consumed.add(item);
        },
        split)) {
      // drain until the pieces are through
    }

    assertEquals(Arrays.asList("a", "b"), consumed);
    assertEquals(0, queue.dropped(), "partitioned work is not lost");
  }

  // Reservations are the single-consumer backing's alone: see WorkQueue#tryReserve.

  @org.junit.jupiter.api.Test
  void reservationHoldsItsPlaceUntilFilled() {
    WorkQueue<String> queue = WorkQueues.createMpscQueue(CAPACITY);
    try (Reservation<String> place = queue.tryReserve()) {
      assertNotNull(place);
      assertTrue(queue.tryPut("behind"), "the rest of the queue stays open for admission");
      assertFalse(queue.process(item -> {}), "the consumer cannot see past an open reservation");
      place.fill("reserved");
    }
    assertEquals(Arrays.asList("reserved", "behind"), drain(queue));
  }

  /** The stall an open reservation causes is why it is an escape hatch and not the default. */
  @org.junit.jupiter.api.Test
  void abandonedReservationReleasesTheConsumer() {
    WorkQueue<String> queue = WorkQueues.createMpscQueue(CAPACITY);
    Reservation<String> place = queue.tryReserve();
    assertNotNull(place);
    queue.tryPut("behind");
    assertFalse(queue.process(item -> {}));

    place.close();

    assertEquals(Arrays.asList("behind"), drain(queue), "the abandoned place is skipped, not held");
    assertEquals(0, queue.dropped(), "abandoning a place the caller claimed is not a rejection");
  }

  @org.junit.jupiter.api.Test
  void reserveFailsWhenThereIsNoRoom() {
    WorkQueue<String> queue = WorkQueues.createMpscQueue(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    assertNull(queue.tryReserve());
    assertEquals(1, queue.dropped(), "a place that could not be claimed counts like a rejection");
  }

  @org.junit.jupiter.api.Test
  void reserveFailsOnceClosed() {
    WorkQueue<String> queue = WorkQueues.createMpscQueue(CAPACITY);
    queue.close();
    assertNull(queue.tryReserve());
  }

  @org.junit.jupiter.api.Test
  void filledReservationsInterleaveWithOrdinaryAdmission() {
    WorkQueue<String> queue = WorkQueues.createMpscQueue(CAPACITY);
    queue.tryPut("first");
    try (Reservation<String> place = queue.tryReserve()) {
      place.fill("second");
    }
    queue.tryPut("third");
    assertEquals(Arrays.asList("first", "second", "third"), drain(queue));
  }

  /**
   * A multi-consumer queue refuses the hatch rather than letting a consumer spin on a held place.
   */
  @org.junit.jupiter.api.Test
  void multiConsumerQueuesRefuseToReserve() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> WorkQueues.createMpmcQueue(CAPACITY).tryReserve());
    assertThrows(
        UnsupportedOperationException.class,
        () -> WorkQueues.createUnboundedMpmcQueue().tryReserve());
  }

  private static List<String> drain(WorkQueue<String> queue) {
    List<String> consumed = new ArrayList<>();
    while (queue.process(consumed::add)) {
      // drain
    }
    return consumed;
  }
}
