package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit tests for {@link AbstractSmokeApp#logFileName(String, Instant)}: the per-app log
 * file name is timestamped (UTC) so a retry — a fresh JVM re-running the class — doesn't clobber
 * the prior run's log.
 */
class SmokeAppLogFileNameTest {

  @Test
  void logFileNameIsTimestampedInUtc() {
    Instant when = Instant.parse("2026-07-30T12:34:56.789Z");
    assertEquals(
        "smoke-app.my-app.2026-07-30-123456.789.log", AbstractSmokeApp.logFileName("my-app", when));
  }

  @Test
  void distinctInstantsYieldDistinctNames() {
    // Two runs (retries) at different instants must not resolve to the same file.
    Instant when = Instant.parse("2026-07-30T12:34:56.789Z");
    assertNotEquals(
        AbstractSmokeApp.logFileName("my-app", when),
        AbstractSmokeApp.logFileName("my-app", when.plusMillis(1)));
  }
}
