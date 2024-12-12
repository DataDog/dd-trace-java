package datadog.trace.api.sampling;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class PerRecordingRateLimiterTest {

  @Test
  public void testLimitApplied() {
    Duration window = Duration.ofMillis(100);
    int limit = 20;
    Duration recordingDurationSeconds = Duration.ofSeconds(1);
    PerRecordingRateLimiter rateLimiter =
        new PerRecordingRateLimiter(window, limit, recordingDurationSeconds, 5);
    // no rate limiting is applied during the first window
    int[] slots = new int[(int) (recordingDurationSeconds.toMillis() / window.toMillis())];
    long start = System.nanoTime();
    while (true) {
      int slot = (int) (NANOSECONDS.toMillis(System.nanoTime() - start) / window.toMillis());
      if (slot >= slots.length) {
        break;
      }
      if (rateLimiter.permit()) {
        slots[slot]++;
      }
    }
    assertTrue(Arrays.stream(slots).max().orElse(limit + 1) <= limit);
    assertTrue(Arrays.stream(slots).sum() <= 2 * limit);
  }
}
