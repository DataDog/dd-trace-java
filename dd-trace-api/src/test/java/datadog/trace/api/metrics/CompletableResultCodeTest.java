package datadog.trace.api.metrics;

import static datadog.trace.api.metrics.CompletableResultCode.ofFailure;
import static datadog.trace.api.metrics.CompletableResultCode.ofSuccess;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.PollingConditions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompletableResultCodeTest {

  @Test
  void completedResultsExposeTheirStatus() {
    CompletableResultCode success = ofSuccess();
    CompletableResultCode failure = ofFailure();

    assertTrue(success.isDone());
    assertTrue(success.isSuccess());
    assertSame(success, success.join(0, MILLISECONDS));
    assertTrue(failure.isDone());
    assertFalse(failure.isSuccess());
    assertSame(failure, failure.join(0, MILLISECONDS));
  }

  @Test
  void firstCompletionWinsAndRunsCallbacksOnce() {
    CompletableResultCode result = new CompletableResultCode();
    AtomicInteger callbacks = new AtomicInteger();

    assertSame(result, result.whenComplete(callbacks::incrementAndGet));
    assertSame(result, result.whenComplete(callbacks::incrementAndGet));
    assertSame(result, result.succeed());
    assertSame(result, result.fail());

    assertTrue(result.isDone());
    assertTrue(result.isSuccess());
    assertSame(result, result.whenComplete(callbacks::incrementAndGet));
    assertTrue(result.isSuccess());
    assertSame(result, result.join(0, MILLISECONDS));
    assertEquals(3, callbacks.get());
  }

  @Test
  void canCompleteWithFailureWithoutCallbacks() {
    CompletableResultCode result = new CompletableResultCode();

    assertSame(result, result.fail());

    assertTrue(result.isDone());
    assertFalse(result.isSuccess());
  }

  @Test
  void callbackFailureDoesNotPreventRemainingCallbacks() {
    CompletableResultCode result = new CompletableResultCode();
    AtomicInteger callbacks = new AtomicInteger();
    IllegalStateException failure = new IllegalStateException("boom");
    result.whenComplete(
        () -> {
          throw failure;
        });
    result.whenComplete(callbacks::incrementAndGet);

    assertSame(failure, assertThrows(IllegalStateException.class, result::succeed));

    assertTrue(result.isSuccess());
    assertEquals(1, callbacks.get());
  }

  @Test
  void callbackErrorDoesNotPreventRemainingCallbacks() {
    CompletableResultCode result = new CompletableResultCode();
    AtomicInteger callbacks = new AtomicInteger();
    AssertionError failure = new AssertionError("boom");
    result.whenComplete(
        () -> {
          throw failure;
        });
    result.whenComplete(callbacks::incrementAndGet);

    assertSame(failure, assertThrows(AssertionError.class, result::succeed));

    assertTrue(result.isSuccess());
    assertEquals(1, callbacks.get());
  }

  @Test
  void resultViewFollowsSourceOutcome() {
    CompletableResultCode source = new CompletableResultCode();
    CompletableResultCode success = source.newResultView();
    CompletableResultCode failure = ofFailure().newResultView();

    source.succeed();

    assertTrue(success.isSuccess());
    assertTrue(failure.isDone());
    assertFalse(failure.isSuccess());
  }

  @Test
  void resultViewCanCompleteIndependently() {
    CompletableResultCode source = new CompletableResultCode();
    CompletableResultCode result = source.newResultView();

    result.fail();
    source.succeed();

    assertTrue(source.isSuccess());
    assertFalse(result.isSuccess());
  }

  @Test
  void independentlyCompletedResultViewRunsItsCallbacksOnlyOnce() {
    CompletableResultCode source = new CompletableResultCode();
    CompletableResultCode result = source.newResultView();
    CompletableResultCode sibling = source.newResultView();
    AtomicInteger resultCallbacks = new AtomicInteger();
    AtomicInteger siblingCallbacks = new AtomicInteger();
    result.whenComplete(resultCallbacks::incrementAndGet);
    sibling.whenComplete(siblingCallbacks::incrementAndGet);

    result.fail();
    source.succeed();

    assertEquals(1, resultCallbacks.get());
    assertEquals(1, siblingCallbacks.get());
    assertFalse(result.isSuccess());
    assertTrue(sibling.isSuccess());
  }

  @Test
  void resultViewChainsCompleteWithoutGrowingTheStack() {
    CompletableResultCode source = new CompletableResultCode();
    CompletableResultCode result = source;
    for (int i = 0; i < 100_000; i++) {
      result = result.newResultView();
    }
    CompletableResultCode sibling = source.newResultView();

    source.succeed();

    assertTrue(result.isSuccess());
    assertTrue(sibling.isSuccess());
  }

  @Test
  void blockedCallbackDoesNotDelaySiblingResultView() throws Exception {
    CompletableResultCode source = new CompletableResultCode();
    CompletableResultCode blocking = source.newResultView();
    CompletableResultCode unaffected = source.newResultView();
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    blocking.whenComplete(
        () -> {
          callbackEntered.countDown();
          try {
            assertTrue(releaseCallback.await(5, SECONDS));
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        });
    Thread completer = new Thread(source::succeed);

    completer.start();
    assertTrue(callbackEntered.await(5, SECONDS));
    try {
      assertTrue(unaffected.join(1, SECONDS).isSuccess());
    } finally {
      releaseCallback.countDown();
    }
    completer.join();
  }

  @Test
  void joinWaitsForCompletion() throws Exception {
    CompletableResultCode result = new CompletableResultCode();
    Thread waiter = new Thread(() -> result.join(30, SECONDS));
    waiter.start();

    try {
      new PollingConditions(5)
          .delay(0.01)
          .eventually(() -> assertEquals(Thread.State.TIMED_WAITING, waiter.getState()));

      result.succeed();
      waiter.join(SECONDS.toMillis(5));
    } finally {
      result.succeed();
      waiter.interrupt();
      waiter.join(SECONDS.toMillis(5));
    }

    assertFalse(waiter.isAlive());
    assertTrue(result.isSuccess());
  }

  @Test
  void joinReturnsIncompleteResultAfterTimeout() {
    CompletableResultCode result = new CompletableResultCode();

    assertSame(result, result.join(0, MILLISECONDS));

    assertFalse(result.isDone());
    assertFalse(result.isSuccess());
  }

  @Test
  void joinPreservesInterruptStatus() {
    CompletableResultCode result = new CompletableResultCode();
    Thread.currentThread().interrupt();

    try {
      assertSame(result, result.join(1, SECONDS));
      assertTrue(Thread.currentThread().isInterrupted());
      assertFalse(result.isDone());
    } finally {
      Thread.interrupted();
    }
  }
}
