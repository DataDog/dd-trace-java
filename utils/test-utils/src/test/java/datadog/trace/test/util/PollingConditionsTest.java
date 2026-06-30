package datadog.trace.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
  void factorMultipliesDelayBetweenAttempts() {
    // Large timeout so the run ends by success (after 4 attempts), never by the deadline; the
    // recording subclass captures the requested delays without actually sleeping, so this is
    // deterministic rather than dependent on wall-clock scheduling.
    RecordingPollingConditions conditions = new RecordingPollingConditions(100);
    conditions.delay(0.02).factor(4); // 20ms base delay, multiplied by 4 after each attempt

    AtomicInteger attempts = new AtomicInteger();
    conditions.eventually(
        () -> {
          if (attempts.incrementAndGet() < 4) {
            fail("not yet");
          }
        });

    assertEquals(4, attempts.get());
    // Three failed attempts -> three delays, each 4x the previous.
    assertEquals(Arrays.asList(20L, 80L, 320L), conditions.requestedSleeps);
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

  @Test
  void rejectsNegativeOrNanConfiguration() {
    // Check conditions parameters
    assertThrows(IllegalArgumentException.class, () -> new PollingConditions(-1));
    assertThrows(IllegalArgumentException.class, () -> new PollingConditions().timeout(-1));
    assertThrows(IllegalArgumentException.class, () -> new PollingConditions().initialDelay(-1));
    assertThrows(IllegalArgumentException.class, () -> new PollingConditions().factor(-2));
    // Check delay and within
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> new PollingConditions().delay(-0.5));
    assertTrue(error.getMessage().contains("delay"), error.getMessage());
    assertTrue(error.getMessage().contains("-0.5"), error.getMessage());
    assertThrows(
        IllegalArgumentException.class, () -> new PollingConditions().within(-1, () -> {}));
  }

  /** Captures the requested delays instead of sleeping, for deterministic back-off assertions. */
  private static final class RecordingPollingConditions extends PollingConditions {
    final List<Long> requestedSleeps = new ArrayList<>();

    RecordingPollingConditions(double timeoutSeconds) {
      super(timeoutSeconds);
    }

    @Override
    void sleep(long millis) {
      requestedSleeps.add(millis);
    }
  }
}
