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
  void joinReturnsAfterCompletion() throws Exception {
    CompletableResultCode result = new CompletableResultCode();
    CountDownLatch started = new CountDownLatch(1);
    Thread completer =
        new Thread(
            () -> {
              started.countDown();
              result.succeed();
            });
    completer.start();

    assertTrue(started.await(5, SECONDS));
    assertSame(result, result.join(5, SECONDS));
    assertTrue(result.isSuccess());
    completer.join();
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
