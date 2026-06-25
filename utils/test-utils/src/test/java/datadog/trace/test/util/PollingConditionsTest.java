package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PollingConditionsTest {

  @Test
  void passesOnFirstAttempt() {
    AtomicInteger attempts = new AtomicInteger();
    new PollingConditions().eventually(attempts::incrementAndGet);
    assertEquals(1, attempts.get());
  }

  @Test
  void succeedsAfterSeveralRetries() {
    AtomicInteger attempts = new AtomicInteger();
    new PollingConditions(2)
        .delay(0.01)
        .eventually(
            () -> {
              if (attempts.incrementAndGet() < 5) {
                fail("not yet");
              }
            });
    assertEquals(5, attempts.get());
  }

  @Test
  void timesOutWithFormattedMessageAndUnderlyingCause() {
    AssertionError error =
        assertThrows(
            AssertionError.class,
            () ->
                new PollingConditions(0.1)
                    .delay(0.01)
                    .eventually(() -> assertEquals(1, 2, "still wrong")));

    assertTrue(error.getMessage().startsWith("Condition not satisfied after"), error.getMessage());
    assertTrue(error.getMessage().contains("attempts"), error.getMessage());

    Throwable cause = error.getCause();
    assertInstanceOf(AssertionError.class, cause);
    assertTrue(cause.getMessage().contains("still wrong"), cause.getMessage());
  }

  @Test
  void withinOverridesConfiguredTimeout() {
    // The instance timeout is huge, but within() must use its own (tiny) timeout and fail fast.
    long start = System.currentTimeMillis();
    assertThrows(
        AssertionError.class,
        () -> new PollingConditions(60).delay(0.01).within(0.1, () -> fail("never")));
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(
        elapsed < 5_000, "within() should honor its own short timeout, took " + elapsed + "ms");
  }

  @Test
  void retriesWhenConditionThrowsCheckedException() {
    AtomicInteger attempts = new AtomicInteger();
    new PollingConditions(2)
        .delay(0.01)
        .eventually(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new IOException("not ready");
              }
            });
    assertEquals(3, attempts.get());
  }

  @Test
  void factorBackOffReducesAttemptCount() {
    // Constant delay: many attempts fit in the window.
    AtomicInteger constant = new AtomicInteger();
    assertThrows(
        AssertionError.class,
        () ->
            new PollingConditions(0.3)
                .delay(0.02)
                .factor(1)
                .eventually(
                    () -> {
                      constant.incrementAndGet();
                      fail("nope");
                    }));

    // Exponential back-off: the delay grows quickly, so far fewer attempts fit.
    AtomicInteger growing = new AtomicInteger();
    assertThrows(
        AssertionError.class,
        () ->
            new PollingConditions(0.3)
                .delay(0.02)
                .factor(4)
                .eventually(
                    () -> {
                      growing.incrementAndGet();
                      fail("nope");
                    }));

    assertTrue(
        growing.get() < constant.get(), "growing=" + growing.get() + " constant=" + constant.get());
  }

  @Test
  void appliesInitialDelayBeforeFirstAttempt() {
    AtomicInteger attempts = new AtomicInteger();
    long start = System.currentTimeMillis();
    new PollingConditions()
        .timeout(2)
        .initialDelay(0.05)
        .delay(0.01)
        .factor(1)
        .eventually(attempts::incrementAndGet);
    long elapsed = System.currentTimeMillis() - start;
    assertEquals(1, attempts.get());
    assertTrue(elapsed >= 40, "initial delay should have been applied, took " + elapsed + "ms");
  }

  @Test
  void interruptedWhileWaitingFailsAndRestoresInterruptFlag() {
    // Pre-interrupt so the first inter-attempt sleep throws InterruptedException immediately.
    Thread.currentThread().interrupt();
    try {
      AssertionError error =
          assertThrows(
              AssertionError.class,
              () -> new PollingConditions(2).delay(0.05).eventually(() -> fail("retry")));
      assertEquals("Interrupted while waiting for condition", error.getMessage());
      assertTrue(
          Thread.currentThread().isInterrupted(), "interrupt flag should have been restored");
    } finally {
      // Clear the flag so it does not leak into other tests sharing this thread.
      Thread.interrupted();
    }
  }
}
