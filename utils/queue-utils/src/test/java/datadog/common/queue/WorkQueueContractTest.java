package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    List<String> consumed = consumeAll(queue);
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
  void collectionAdmissionReportsRejectedElements(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    Collection<String> rejected = queue.tryPutBatch(Arrays.asList("a", "b", "c", "d", "e", "f"));
    assertEquals(Arrays.asList("e", "f"), new ArrayList<>(rejected));
    assertEquals(2, queue.dropped());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void exceptionHandlerSeesTheFailureAndTheItemIsDropped(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");

    List<Throwable> seen = new ArrayList<>();
    assertTrue(
        queue.process(
            item -> {
              throw new IllegalStateException("boom");
            },
            (ExceptionHandler) seen::add));

    assertEquals(1, seen.size());
    assertEquals("boom", seen.get(0).getMessage());
    assertEquals(1, queue.dropped());
    assertEquals(0, queue.size());
    assertFalse(queue.process(item -> fail("nothing should be left")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void exceptionHandlerIsNotCalledWhenTheConsumerSucceeds(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");

    List<String> consumed = new ArrayList<>();
    assertTrue(
        queue.process(
            consumed::add,
            (ExceptionHandler) failure -> fail("handler ran for a consumer that did not throw")));

    assertEquals(Arrays.asList("a"), consumed);
    assertEquals(0, queue.dropped());
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
    assertEquals(Arrays.asList("a"), consumeAll(queue));
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

  // A reservation claims capacity on every backing; only the array backing also holds position.

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void reservationClaimsCapacityUpFront(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    try (Reservation<String> place = queue.tryReserve()) {
      assertNotNull(place);
      assertEquals(1, queue.size(), "the claim costs capacity before the element exists");
      for (int i = 0; i < CAPACITY - 1; i++) {
        assertTrue(queue.tryPut("e" + i));
      }
      assertFalse(queue.tryPut("overflow"), "the claimed place is not available to anyone else");
      place.fill("reserved");
    }
    assertTrue(
        consumeAll(queue).contains("reserved"), "filling a claimed place cannot be rejected");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void abandonedReservationYieldsNothingAndGivesTheCapacityBack(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    Reservation<String> place = queue.tryReserve();
    assertNotNull(place);
    place.close();

    // The array backing reclaims the slot as the consumer passes over it rather than at close, so
    // the capacity is back once the queue has been drained, not necessarily the instant it is
    // abandoned. What both backings promise is that nothing is ever consumed for it.
    assertTrue(consumeAll(queue).isEmpty(), "an abandoned place produces no element");
    assertEquals(0, queue.size());
    assertEquals(0, queue.dropped(), "abandoning a place the caller claimed is not a rejection");
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i), "the abandoned capacity is usable again");
    }
    assertEquals(CAPACITY, consumeAll(queue).size());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void reserveFailsWhenThereIsNoRoom(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    Reservation<String> refused = queue.tryReserve();
    assertFalse(refused.granted(), "a refusal is a reservation, never null");
    assertEquals(1, queue.dropped(), "a place that could not be claimed counts like a rejection");

    refused.fill("discarded");
    refused.close();
    assertEquals(CAPACITY, queue.size(), "filling a refusal changes nothing and does not throw");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void reserveFailsOnceClosed(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.close();
    assertFalse(queue.tryReserve().granted());
  }

  /** The array backing claims a slot, so the element keeps the position it was reserved at. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void reservationJoinsWhereItIsFilledRatherThanWhereItWasClaimed(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("first");
    List<String> consumed = new ArrayList<>();

    try (Reservation<String> place = queue.tryReserve()) {
      assertTrue(queue.tryPut("behind"), "the rest of the queue stays open for admission");
      assertEquals(
          Arrays.asList("first", "behind"),
          consumeAll(queue),
          "a reservation holds no position, so nothing is held in front of the consumer");
      place.fill("filled late");
    }

    consumed.addAll(consumeAll(queue));
    assertEquals(Arrays.asList("filled late"), consumed, "the order is the fill order");
  }

  /**
   * The hazard a position-holding reservation would have: one thread that reserves and then drains
   * would be waiting on itself.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aThreadMayReserveAndConsume(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("waiting");

    try (Reservation<String> place = queue.tryReserve()) {
      assertEquals(1, queue.process(10, item -> {}), "consumption is not blocked by the claim");
      place.fill("filled");
    }

    assertEquals(Arrays.asList("filled"), consumeAll(queue));
  }

  @org.junit.jupiter.api.Test
  void unboundedReservationAlwaysSucceeds() {
    WorkQueue<String> queue = WorkQueues.createUnboundedMpmcQueue();
    for (int i = 0; i < 1000; i++) {
      try (Reservation<String> place = queue.tryReserve()) {
        assertNotNull(place);
        place.fill("e" + i);
      }
    }
    assertEquals(1000, queue.size());
    assertEquals(0, queue.dropped());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processStopsAtTheLimit(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      queue.tryPut("e" + i);
    }
    List<String> consumed = new ArrayList<>();

    assertEquals(2, queue.process(2, consumed::add));

    assertEquals(Arrays.asList("e0", "e1"), consumed);
    assertEquals(CAPACITY - 2, queue.size(), "the rest of the batch is still there");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processStopsWhenTheQueueRunsDry(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    queue.tryPut("b");
    List<String> consumed = new ArrayList<>();

    assertEquals(
        2,
        queue.process(100, consumed::add),
        "a count short of the limit is how a caller learns there is no more work");

    assertEquals(Arrays.asList("a", "b"), consumed);
    assertEquals(0, queue.process(100, consumed::add), "and an empty queue drains nothing");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processConsumesNothingForAnEmptyBatch(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");

    assertEquals(0, queue.process(0, item -> fail("nothing may be consumed")));

    assertEquals(1, queue.size());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processPassesTheContextToEveryItem(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    queue.tryPut("b");
    List<String> consumed = new ArrayList<>();

    assertEquals(2, queue.process(10, consumed, List::add));

    assertEquals(Arrays.asList("a", "b"), consumed);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void processAbandonsTheRestOfTheBatchWhenTheConsumerThrows(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    queue.tryPut("b");
    queue.tryPut("c");
    List<String> consumed = new ArrayList<>();

    assertThrows(
        IllegalStateException.class,
        () ->
            queue.process(
                10,
                item -> {
                  consumed.add(item);
                  if ("b".equals(item)) {
                    throw new IllegalStateException("boom");
                  }
                }));

    assertEquals(Arrays.asList("a", "b"), consumed, "the failing item was handed over");
    assertEquals(1, queue.size(), "what was behind it is left for the next drain");
    assertEquals(0, queue.dropped(), "a failure the caller sees is not a drop");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void openReservationHoldsCapacityWithoutHoldingUpTheBatch(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("first");
    List<String> consumed = new ArrayList<>();

    try (Reservation<String> place = queue.tryReserve()) {
      queue.tryPut("behind");

      assertEquals(2, queue.process(10, consumed::add), "the batch runs past the open claim");
      assertEquals(Arrays.asList("first", "behind"), consumed);
      assertEquals(1, queue.size(), "the claimed place is still spent");

      place.fill("reserved");
    }

    assertEquals(1, queue.process(10, consumed::add));
    assertEquals(Arrays.asList("first", "behind", "reserved"), consumed);
    assertEquals(0, queue.size());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void admitsFromTwoContexts(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);

    assertTrue(queue.tryPut("a", "b", (first, second) -> first + second));

    assertEquals(Arrays.asList("ab"), consumeAll(queue));
  }

  /** The point of the whole API, in its two-context form: a rejected element is never built. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void doesNotInvokeTwoContextProducerWhenFull(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      queue.tryPut("e" + i);
    }
    AtomicBoolean produced = new AtomicBoolean();

    assertFalse(
        queue.tryPut(
            produced,
            "unused",
            (flag, ignored) -> {
              flag.set(true);
              return "built";
            }));

    assertFalse(produced.get(), "a full queue must not build what it is going to reject");
    assertEquals(1, queue.dropped());
  }

  private static List<String> consumeAll(WorkQueue<String> queue) {
    List<String> consumed = new ArrayList<>();
    while (queue.process(consumed::add)) {
      // drain
    }
    return consumed;
  }
}
