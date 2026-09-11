package datadog.common.queue;

import static java.util.concurrent.TimeUnit.SECONDS;
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
import java.util.concurrent.CountDownLatch;
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
  void admitsUpToCapacityThenRefuses(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    assertEquals(CAPACITY, queue.size());
    assertFalse(queue.tryPut("overflow"));
    assertEquals(CAPACITY, queue.size());
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
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void collectionAdmissionReportsRejectedElements(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    Collection<String> rejected = queue.tryPutBatch(Arrays.asList("a", "b", "c", "d", "e", "f"));
    assertEquals(Arrays.asList("e", "f"), new ArrayList<>(rejected));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void transformingBatchAdmissionAppliesTheContextToEverySourceElement(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    int admitted =
        queue.tryPutBatch(Arrays.asList(1, 2, 3), "x", (source, suffix) -> source + suffix);
    assertEquals(3, admitted);
    assertEquals(Arrays.asList("1x", "2x", "3x"), consumeAll(queue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aDeclinedSourceElementIsNotAdmitted(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    // Every other element declined. Returning null is the caller's own decision, so it does not
    // count against the admitted total: the caller already knows it declined.
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6),
            "x",
            (source, suffix) -> source % 2 == 0 ? null : source + suffix);
    assertEquals(3, admitted);
    assertEquals(Arrays.asList("1x", "3x", "5x"), consumeAll(queue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void decliningLeavesTheClaimedPlaceAvailableToTheRestOfTheBatch(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    // Nearly twice capacity in source elements, the even ones declined: the place claimed for a
    // declined element has to go back, or the batch would run out of room after CAPACITY source
    // elements rather than after CAPACITY admitted ones.
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6, 7),
            "x",
            (source, suffix) -> source % 2 == 0 ? null : source + suffix);
    assertEquals(CAPACITY, admitted);
    assertEquals(Arrays.asList("1x", "3x", "5x", "7x"), consumeAll(queue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void theShortfallIsExactWhenTheCallerKnowsWhatItMeantToAdmit(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    List<Integer> asked = new ArrayList<>();
    int intended = 6;
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6),
            "x",
            (source, suffix) -> {
              asked.add(source);
              return source + suffix;
            });
    assertEquals(CAPACITY, admitted);
    // The whole point of the count: a caller that declined nothing gets its loss by subtraction.
    assertEquals(2, intended - admitted);
    // And the producer was only ever asked about elements there was already room for.
    assertEquals(Arrays.asList(1, 2, 3, 4), asked);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aSourceElementTheProducerWouldHaveDeclinedIsStillRefusedOnceFull(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    // The odd elements fill the queue exactly, so element 8 never gets a place -- even though the
    // producer would have declined it. The place is claimed before the producer is asked, so the
    // queue cannot know that, and reports what is true from where it stands: it could not ask.
    // This is why the reject handler is approximate for a declining producer and the shortfall
    // by subtraction is not.
    List<Integer> refused = new ArrayList<>();
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8),
            "x",
            (source, suffix) -> source % 2 == 0 ? null : source + suffix,
            refused::add);
    assertEquals(CAPACITY, admitted);
    assertEquals(Arrays.asList("1x", "3x", "5x", "7x"), consumeAll(queue));
    assertEquals(
        Arrays.asList(8),
        refused,
        "element 8 was refused for want of a place, though it would have been declined");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void transformingBatchAdmissionAdmitsNothingOnceClosed(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.close();
    AtomicBoolean asked = new AtomicBoolean();
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2),
            "x",
            (source, suffix) -> {
              asked.set(true);
              return source + suffix;
            });
    assertEquals(0, admitted);
    assertFalse(asked.get(), "a closed queue must not ask the producer for anything");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aRejectHandlerSeesEverySourceElementThatCouldNotBeAdmitted(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    List<Integer> rejected = new ArrayList<>();
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6),
            "x",
            (source, suffix) -> source + suffix,
            rejected::add);
    assertEquals(CAPACITY, admitted);
    assertEquals(Arrays.asList(5, 6), rejected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aRejectHandlerDoesNotSeeElementsTheProducerDeclined(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    List<Integer> rejected = new ArrayList<>();
    // Six source elements, three declined, three admitted -- the queue never fills, so nothing was
    // refused and the handler is never called. A decline is not a rejection.
    int admitted =
        queue.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6),
            "x",
            (source, suffix) -> source % 2 == 0 ? null : source + suffix,
            rejected::add);
    assertEquals(3, admitted);
    assertTrue(rejected.isEmpty(), "a declined element is the caller's own decision");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aThrowingTransformGivesBackItsPlaceAndPropagates(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    List<Integer> source = Arrays.asList(1, 2, 3);
    assertThrows(
        IllegalStateException.class,
        () ->
            queue.tryPutBatch(
                source,
                "x",
                (element, suffix) -> {
                  if (element == 2) {
                    throw new IllegalStateException("boom");
                  }
                  return element + suffix;
                }));
    // The place claimed for the failed element went back, so the queue still holds capacity for
    // three more admissions beyond the one that succeeded.
    assertEquals(1, queue.size());
    assertTrue(queue.tryPutBatch("a", "b", "c").isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void exceptionHandlerSeesTheFailureAndTheItemIsDropped(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");

    List<String> seen = new ArrayList<>();
    assertTrue(
        queue.processOrHandle(
            item -> {
              throw new IllegalStateException("boom");
            },
            (item, failure) -> seen.add(item + ":" + failure.getMessage())));

    assertEquals(Arrays.asList("a:boom"), seen, "the handler is told which item died");
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
        queue.processOrHandle(
            consumed::add,
            (item, failure) -> fail("handler ran for a consumer that did not throw")));

    assertEquals(Arrays.asList("a"), consumed);
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
        queue.processOrRetry(
            item -> {
              throw new IllegalStateException("boom");
            },
            giveUp),
        "the return value reports work found, not consumer success");
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

    while (queue.processOrRetry(
        item -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("boom");
        },
        strategy)) {
      // drain until the strategy stops resubmitting
    }

    assertEquals(2, attempts.get(), "consumed twice: original plus one retry");
    assertEquals(Arrays.asList(1, 2), reported, "attempt counts survive re-admission");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void maxRetriesBoundsResubmission(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.tryPut("a");
    AtomicInteger attempts = new AtomicInteger();
    RetryStrategy<String> strategy = new MaxRetries<>(3);

    while (queue.processOrRetry(
        item -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("boom");
        },
        strategy)) {
      // drain
    }

    assertEquals(4, attempts.get(), "three retries on top of the original consumption");
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
  void aBoundedMpmcQueueRoundsItsCapacityUpToTheRing() {
    // The bound is the ring's own capacity, not the number asked for, so the counter and the ring
    // can never disagree about what full means. A caller asking for 100 gets 128.
    WorkQueue<String> queue = WorkQueues.createMpmcQueue(100);
    for (int i = 0; i < 128; i++) {
      assertTrue(queue.tryPut("e" + i), "place " + i + " should have been there");
    }
    assertFalse(queue.tryPut("overflow"));
    assertEquals(128, queue.size());
  }

  @org.junit.jupiter.api.Test
  void aBoundedMpmcQueueTooSmallForTheRingIsRaisedRatherThanRefused() {
    // JCTools will not build a ring of one. Rounding up is the established answer to a capacity
    // the ring cannot honour, and throwing here would only surprise a caller who asked for less.
    WorkQueue<String> queue = WorkQueues.createMpmcQueue(1);
    assertTrue(queue.tryPut("a"));
    assertTrue(queue.tryPut("b"));
    assertFalse(queue.tryPut("c"));
  }

  @org.junit.jupiter.api.Test
  void severalProducersAndConsumersOnAnArrayRingLoseNothingAndLeakNoPlace()
      throws InterruptedException {
    // The MPMC ring refuses an offer while another thread is midway through publishing to the slot
    // in question -- on a queue that is neither full nor empty. Admission claims a place before it
    // stores, so such a refusal can only mean "not yet" and the backing retries. If that reasoning
    // is wrong, it is wrong here: elements go missing without any producer being told, or their
    // places never come back. The drain side is not riding out the same window -- poll spins for a
    // pending publish rather than reporting empty -- so a false from process here means another
    // consumer got there first.
    int capacity = 64;
    int producers = 4;
    int consumers = 4;
    int perProducer = 20_000;
    WorkQueue<String> queue = WorkQueues.createMpmcQueue(capacity);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger admitted = new AtomicInteger();
    AtomicInteger refused = new AtomicInteger();
    AtomicInteger consumed = new AtomicInteger();
    AtomicBoolean draining = new AtomicBoolean(true);
    List<Thread> threads = new ArrayList<>();
    for (int c = 0; c < consumers; c++) {
      Thread drain =
          new Thread(
              () -> {
                while (draining.get()) {
                  if (queue.process(item -> consumed.incrementAndGet())) {
                    continue;
                  }
                  Thread.yield();
                }
                // Once the producers are done, take what is left.
                while (queue.process(item -> consumed.incrementAndGet())) {
                  // drain to empty
                }
              });
      threads.add(drain);
      drain.start();
    }
    List<Thread> admitters = new ArrayList<>();
    for (int p = 0; p < producers; p++) {
      Thread admit =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                for (int i = 0; i < perProducer; i++) {
                  if (queue.tryPut("e" + i)) {
                    admitted.incrementAndGet();
                  } else {
                    refused.incrementAndGet();
                  }
                }
              });
      admitters.add(admit);
      admit.start();
    }
    start.countDown();
    for (Thread admit : admitters) {
      admit.join(SECONDS.toMillis(30));
      assertFalse(admit.isAlive(), "producer did not finish");
    }
    draining.set(false);
    for (Thread drain : threads) {
      drain.join(SECONDS.toMillis(30));
      assertFalse(drain.isAlive(), "consumer did not finish");
    }
    assertEquals(
        producers * perProducer,
        admitted.get() + refused.get(),
        "every element was either admitted or refused, and the refusal was reported");
    assertEquals(admitted.get(), consumed.get(), "every admitted element came back out");
    // The decisive one: if a retry had given up and the place had not come back, or a spurious
    // empty read had stranded an element, the queue would now hold fewer than capacity places.
    for (int i = 0; i < capacity; i++) {
      assertTrue(queue.tryPut("after" + i), "place " + i + " was lost");
    }
    assertFalse(queue.tryPut("overflow"));
  }

  @org.junit.jupiter.api.Test
  void unboundedQueueNeverRejects() {
    WorkQueue<String> queue = WorkQueues.createUnboundedMpmcQueue();
    for (int i = 0; i < 1000; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    assertEquals(1000, queue.size());
  }

  @org.junit.jupiter.api.Test
  void unboundedQueueStillCloses() {
    WorkQueue<String> queue = WorkQueues.createUnboundedMpmcQueue();
    queue.close();
    assertFalse(queue.tryPut("a"));
  }

  /**
   * Closing is a bias applied to the permit count rather than a flag beside it, so the three ways
   * that encoding could leak are worth pinning: applying it twice, reading a size through it, and
   * giving places back underneath it.
   *
   * <p>Two of the three pin behaviour they cannot currently catch a slip in, and it is worth being
   * straight about why: the offset is far enough from either threshold that neither repeated closes
   * nor a full queue's worth of returned places can reach it. They are guards against a future
   * change to the offset or to the width of either.
   *
   * <p>The size case is different now that {@code size()} clamps to the capacity. The offset is a
   * multiple of 2^32, so the cast back to {@code int} used to erase the bias whether or not the
   * unbiasing was there, and this test passed either way; a clamped report turns a missing unbias
   * into a full-looking queue instead, which is a number this test rejects.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void closingTwiceSaysWhatClosingOnceSaid(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);

    queue.close();
    queue.close();
    queue.close();

    assertTrue(queue.isClosed(), "still closed, not closed three times over");
    assertFalse(queue.tryPut("a"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aClosedQueueStillReportsWhatItHolds(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertTrue(queue.tryPut("a"));
    assertTrue(queue.tryPut("b"));

    queue.close();

    assertEquals(2, queue.size(), "closing must not be visible as a size");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void drainingAfterCloseDoesNotReopen(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    queue.close();

    // Every drained element hands a place back, so a full queue's worth of releases runs the
    // count as far back toward the bias as it can go.
    List<String> drained = new ArrayList<>();
    while (queue.process(drained::add)) {
      // drain it dry
    }

    assertEquals(CAPACITY, drained.size(), "close does not stop consumption");
    assertEquals(0, queue.size());
    assertTrue(queue.isClosed(), "returned places must not climb out of the closed state");
    assertFalse(queue.tryPut("after"));
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

    while (queue.processOrRetry(
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
  }

  // --- What a null means, one test per place it can appear. ---

  /**
   * The leak this guards against is silent and permanent: claiming a place and then throwing out of
   * the backing would shrink capacity by one for the life of the queue, once per call.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aNullElementThrowsWithoutSpendingAPlace(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    // A null-valued variable, not a literal: a bare tryPut(null) does not compile, because it
    // cannot tell tryPut(T) from tryPut(Producer). Real callers reach this path through a field.
    String absent = null;
    assertThrows(NullPointerException.class, () -> queue.tryPut(absent));
    assertEquals(0, queue.size());
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i), "the refused call must not have cost the queue a place");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aNullElementThrowsOutOfABatchAndAbandonsTheRest(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertThrows(
        NullPointerException.class, () -> queue.tryPutBatch(Arrays.asList("a", null, "b")));
    assertEquals(Arrays.asList("a"), consumeAll(queue), "what came before the null is admitted");
    // A batch claims places for a run of elements before it looks at any of them, so the throw
    // leaves places claimed for the null and for everything behind it. They have to come back, or
    // every null costs the queue room permanently.
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i), "the throw must not have cost the queue a place");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void fillingAReservationWithNullThrowsAndTheReservationStillReleases(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    Reservation<String> place = queue.tryReserve();
    assertTrue(place.granted());
    assertThrows(NullPointerException.class, () -> place.fill(null));
    place.close();
    assertEquals(0, queue.size());
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
  }

  /**
   * A producer declining means the same thing in the single-element forms as it does in a batch:
   * nothing was lost, so nothing is counted. The place has to come back either way.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aProducerDecliningIsNotAdmitted(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertFalse(queue.tryPut(() -> null));
    assertFalse(queue.tryPut("ctx", ctx -> null));
    assertFalse(queue.tryPut("one", "two", (first, second) -> null));
    assertEquals(0, queue.size());
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i), "every declined place must have been given back");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aNullProducerThrowsAndGivesBackTheClaimedPlace(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertThrows(NullPointerException.class, () -> queue.tryPut("ctx", null));
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i), "the place claimed before the call must not be stranded");
    }
  }

  /** A context is the caller's own value; the queue carries it and never looks at it. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aNullContextIsCarriedThroughToTheProducer(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    assertTrue(queue.tryPut((String) null, ctx -> ctx == null ? "absent" : "present"));
    assertTrue(queue.tryPut(null, null, (first, second) -> first == null ? "both" : "neither"));
    assertEquals(
        1,
        queue.tryPutBatch(
            Arrays.asList(1), null, (source, context) -> context == null ? "null ctx" : "ctx"));
    assertEquals(Arrays.asList("absent", "both", "null ctx"), consumeAll(queue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aNullRejectHandlerSaysWhatOmittingItSays(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> withNull = factory.apply(CAPACITY);
    int admittedWithNull =
        withNull.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6), "x", (source, suffix) -> source + suffix, null);
    WorkQueue<String> without = factory.apply(CAPACITY);
    int admittedWithout =
        without.tryPutBatch(
            Arrays.asList(1, 2, 3, 4, 5, 6), "x", (source, suffix) -> source + suffix);
    assertEquals(CAPACITY, admittedWithNull);
    assertEquals(admittedWithout, admittedWithNull);
    assertEquals(consumeAll(without), consumeAll(withNull));
  }

  // --- Retry is a step, not an outcome. ---

  /**
   * A retry has to claim a place like any other admission, so a queue that refilled behind the
   * failed item refuses it. This is the one loss with no channel back to the caller: {@code
   * processOrRetry} returns only whether there was an item, so the strategy's own return is the
   * only report that the item was given up on, and nothing here reads it.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aRefusedRetryIsReportedToTheStrategyWhenTheQueueRefilled(
      String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    AtomicBoolean retryRefused = new AtomicBoolean();
    assertTrue(
        queue.processOrRetry(
            item -> {
              throw new IllegalStateException("consumer failed on " + item);
            },
            (item, attempt, failure, retryQueue) -> {
              // Take the place the failed item vacated, so the retry has nowhere to land.
              assertTrue(queue.tryPut("filler"));
              retryRefused.set(!retryQueue.retry(item));
              return false;
            }));
    assertTrue(retryRefused.get(), "the queue was full again, so the retry had to be refused");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aSuccessfulRetryTakesAPlaceAgain(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    for (int i = 0; i < CAPACITY; i++) {
      assertTrue(queue.tryPut("e" + i));
    }
    AtomicInteger seenAttempt = new AtomicInteger();
    assertTrue(
        queue.processOrRetry(
            item -> {
              throw new IllegalStateException("consumer failed on " + item);
            },
            (item, attempt, failure, retryQueue) -> {
              seenAttempt.set(attempt);
              return retryQueue.retry(item);
            }));
    assertEquals(1, seenAttempt.get(), "the first failure reports attempt 1");
    assertEquals(CAPACITY, queue.size(), "the retried item took a place again");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aBatchLongerThanOneClaimKeepsClaiming(String name, IntFunction<WorkQueue<String>> factory) {
    // Deliberately more than the per-claim cap, so the batch cannot be served by one claim. A
    // short grant is not the end of the batch, and this is what says so. A power of two, because
    // the MPSC backing takes the bound from the ring it rounded up to.
    int size = 64;
    WorkQueue<String> queue = factory.apply(size);
    List<String> elements = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      elements.add("e" + i);
    }
    assertTrue(queue.tryPutBatch(elements).isEmpty(), "there was room for all of them");
    assertEquals(size, queue.size());
    assertEquals(elements, consumeAll(queue));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aBatchLongerThanOneClaimStopsExactlyAtCapacity(
      String name, IntFunction<WorkQueue<String>> factory) {
    int size = 64;
    WorkQueue<String> queue = factory.apply(size);
    List<String> elements = new ArrayList<>();
    for (int i = 0; i < size + 10; i++) {
      elements.add("e" + i);
    }
    Collection<String> rejected = queue.tryPutBatch(elements);
    assertEquals(10, rejected.size(), "short by exactly the overflow, not by a whole claim");
    assertEquals(elements.subList(size, size + 10), new ArrayList<>(rejected));
    assertEquals(size, queue.size());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void aBatchClaimsNothingOnceClosed(String name, IntFunction<WorkQueue<String>> factory) {
    WorkQueue<String> queue = factory.apply(CAPACITY);
    queue.close();
    List<String> elements = Arrays.asList("a", "b", "c");
    assertEquals(elements, new ArrayList<>(queue.tryPutBatch(elements)));
    assertEquals(0, queue.size(), "a closed queue took nothing");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundedQueues")
  void batchesAndSingleAdmissionsRacingCannotBetweenThemPassTheBound(
      String name, IntFunction<WorkQueue<String>> factory) throws InterruptedException {
    // A batch claim spends several places with one add and gives back what it could not use. If
    // the giving back were wrong in either direction the bound would move: too little back and the
    // queue silently shrinks, too much and it overfills. Racing the two shapes against each other
    // is what makes an arithmetic slip visible.
    int capacity = 64;
    int threads = 8;
    WorkQueue<String> queue = factory.apply(capacity);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger admitted = new AtomicInteger();
    List<Thread> racers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      boolean batching = t % 2 == 0;
      Thread racer =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (batching) {
                  List<String> batch = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h");
                  admitted.addAndGet(batch.size() - queue.tryPutBatch(batch).size());
                } else {
                  for (int i = 0; i < 8; i++) {
                    if (queue.tryPut("s" + i)) {
                      admitted.incrementAndGet();
                    }
                  }
                }
              });
      racers.add(racer);
      racer.start();
    }
    start.countDown();
    for (Thread racer : racers) {
      racer.join(SECONDS.toMillis(10));
      assertFalse(racer.isAlive(), "racer did not finish");
    }
    assertEquals(admitted.get(), queue.size(), "every admission that reported took a place");
    assertTrue(admitted.get() <= capacity, "the bound held: " + admitted.get() + " > " + capacity);
    // Whatever was refused, the places are all accounted for: draining gives back exactly what
    // went in, and the queue then takes a full capacity again.
    assertEquals(admitted.get(), consumeAll(queue).size());
    for (int i = 0; i < capacity; i++) {
      assertTrue(queue.tryPut("after" + i), "place " + i + " was lost");
    }
    assertFalse(queue.tryPut("overflow"));
  }

  /**
   * A backing that claims to be unable to store anything, so the one outcome no real backing
   * produces on demand -- a refusal of an element a place was already claimed for -- can be tested
   * at all. {@link MpmcWorkQueue} reaches it by exhausting its retry bound, which a test cannot
   * provoke reliably.
   */
  private static final class RefusingWorkQueue<T> extends BaseWorkQueue<T> {
    RefusingWorkQueue(int capacity) {
      super(capacity);
    }

    @Override
    boolean store(Object element) {
      return false;
    }

    @Override
    Object retrieve() {
      return null;
    }
  }

  @org.junit.jupiter.api.Test
  void aRefusedFillGivesThePlaceBack() {
    RefusingWorkQueue<String> queue = new RefusingWorkQueue<>(1);
    try (Reservation<String> place = queue.tryReserve()) {
      assertTrue(place.granted());
      place.fill("lost");
    }
    assertEquals(0, queue.size(), "the place was not given back");
    // And the capacity is still usable, which is the part a leaked place would break.
    assertTrue(queue.tryReserve().granted(), "the place was lost for good");
  }

  @org.junit.jupiter.api.Test
  void aRefusedStoreOnAPlainPutIsReportedAndLeaksNoPlace() {
    RefusingWorkQueue<String> queue = new RefusingWorkQueue<>(1);
    assertFalse(queue.tryPut("lost"));
    assertEquals(0, queue.size());
    // And the place came back, which is the part a leak would break.
    assertTrue(queue.tryReserve().granted(), "the place was lost for good");
  }

  private static List<String> consumeAll(WorkQueue<String> queue) {
    List<String> consumed = new ArrayList<>();
    while (queue.process(consumed::add)) {
      // drain
    }
    return consumed;
  }
}
