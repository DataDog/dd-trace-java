package datadog.metrics.impl.statsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DDAgentStatsDConnectionTest {

  @Test
  void connectRetryDelayDoublesUpToSixtySecondsAndNeverStops() {
    assertEquals(10, DDAgentStatsDConnection.connectRetryDelaySeconds(1));
    assertEquals(20, DDAgentStatsDConnection.connectRetryDelaySeconds(2));
    assertEquals(40, DDAgentStatsDConnection.connectRetryDelaySeconds(3));
    assertEquals(60, DDAgentStatsDConnection.connectRetryDelaySeconds(4));
    assertEquals(60, DDAgentStatsDConnection.connectRetryDelaySeconds(1_000));
    assertEquals(60, DDAgentStatsDConnection.connectRetryDelaySeconds(Integer.MAX_VALUE));
  }

  @Test
  void writeErrorsAboutFifteenSecondsApartRecreateAfterThirtySeconds() {
    DDAgentStatsDConnection.WriteFailureStreak streak =
        new DDAgentStatsDConnection.WriteFailureStreak();

    assertFalse(streak.recordError(0));
    assertFalse(streak.recordError(15_000));
    assertTrue(streak.recordError(30_000));

    // the streak starts over after a recreate
    assertFalse(streak.recordError(45_000));
    assertFalse(streak.recordError(60_000));
    assertTrue(streak.recordError(75_000));
  }

  @Test
  void isolatedWriteErrorsDoNotRecreate() {
    DDAgentStatsDConnection.WriteFailureStreak streak =
        new DDAgentStatsDConnection.WriteFailureStreak();

    assertFalse(streak.recordError(0));
    assertFalse(streak.recordError(30_000));
  }

  @Test
  void briefBurstOfWriteErrorsDoesNotRecreate() {
    DDAgentStatsDConnection.WriteFailureStreak streak =
        new DDAgentStatsDConnection.WriteFailureStreak();

    for (long now = 0; now < 29_000; now += 100) {
      assertFalse(streak.recordError(now));
    }
  }

  @Test
  void gapOfMoreThanSixtySecondsStartsANewStreak() {
    DDAgentStatsDConnection.WriteFailureStreak streak =
        new DDAgentStatsDConnection.WriteFailureStreak();

    assertFalse(streak.recordError(0));
    assertFalse(streak.recordError(15_000));
    assertFalse(streak.recordError(75_001));
    assertFalse(streak.recordError(90_000));
    assertTrue(streak.recordError(105_001));
  }
}
