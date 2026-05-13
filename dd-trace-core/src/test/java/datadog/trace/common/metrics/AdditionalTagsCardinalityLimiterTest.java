package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AdditionalTagsCardinalityLimiter.BLOCKED_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdditionalTagsCardinalityLimiterTest {

  @Test
  void belowLimitAdmitsAllValues() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(100, HealthMetrics.NO_OP);
    for (int i = 0; i < 99; i++) {
      String v = "v" + i;
      assertEquals(v, limiter.admitOrBlock("region", v));
    }
  }

  @Test
  void atLimitNextNewValueIsBlocked() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(3, HealthMetrics.NO_OP);
    assertEquals("a", limiter.admitOrBlock("region", "a"));
    assertEquals("b", limiter.admitOrBlock("region", "b"));
    assertEquals("c", limiter.admitOrBlock("region", "c"));
    assertEquals(BLOCKED_VALUE, limiter.admitOrBlock("region", "d"));
  }

  @Test
  void alreadyAdmittedValueStaysAdmittedAfterCapHit() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(3, HealthMetrics.NO_OP);
    limiter.admitOrBlock("region", "a");
    limiter.admitOrBlock("region", "b");
    limiter.admitOrBlock("region", "c");
    limiter.admitOrBlock("region", "d"); // blocked
    assertEquals("a", limiter.admitOrBlock("region", "a"));
    assertEquals("b", limiter.admitOrBlock("region", "b"));
    assertNotEquals(BLOCKED_VALUE, limiter.admitOrBlock("region", "c"));
  }

  @Test
  void differentTagsAreIndependent() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(2, HealthMetrics.NO_OP);
    limiter.admitOrBlock("customer_id", "x");
    limiter.admitOrBlock("customer_id", "y");
    assertEquals(BLOCKED_VALUE, limiter.admitOrBlock("customer_id", "z"));
    // region should be completely unaffected
    assertEquals("us-east-1", limiter.admitOrBlock("region", "us-east-1"));
    assertEquals("eu-west-1", limiter.admitOrBlock("region", "eu-west-1"));
    assertEquals(BLOCKED_VALUE, limiter.admitOrBlock("region", "ap-south-1"));
  }

  @Test
  void resetReadmitsPreviouslyBlockedValues() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(2, HealthMetrics.NO_OP);
    limiter.admitOrBlock("region", "a");
    limiter.admitOrBlock("region", "b");
    assertEquals(BLOCKED_VALUE, limiter.admitOrBlock("region", "c"));
    limiter.reset();
    assertEquals("c", limiter.admitOrBlock("region", "c"));
  }

  @Test
  void healthMetricFiresOnBlock() {
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(2, health);
    limiter.admitOrBlock("region", "a");
    limiter.admitOrBlock("region", "b");
    assertEquals(0, health.blocked.size());
    limiter.admitOrBlock("region", "c"); // blocked
    limiter.admitOrBlock("region", "d"); // blocked
    assertEquals(2, health.blocked.size());
    assertEquals("region", health.blocked.get(0));
    assertEquals("region", health.blocked.get(1));
  }

  @Test
  void noteBlockedDueToLengthFiresHealthMetric() {
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(100, health);
    limiter.noteBlockedDueToLength("region", 500, 250);
    assertEquals(1, health.blocked.size());
    assertEquals("region", health.blocked.get(0));
  }

  @Test
  void lengthAndCardinalityBlocksAreCountedSeparatelyInHealth() {
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(2, health);
    // exhaust cardinality
    limiter.admitOrBlock("region", "a");
    limiter.admitOrBlock("region", "b");
    limiter.admitOrBlock("region", "c"); // cardinality block -> 1 health event
    // length block on same tag -> 2 health events total
    limiter.noteBlockedDueToLength("region", 500, 250);
    assertEquals(2, health.blocked.size());
    // reset rearms both branches
    limiter.reset();
    limiter.admitOrBlock("region", "x");
    limiter.admitOrBlock("region", "y");
    limiter.admitOrBlock("region", "z"); // cardinality block again -> 3
    limiter.noteBlockedDueToLength("region", 500, 250); // length block again -> 4
    assertEquals(4, health.blocked.size());
  }

  private static final class RecordingHealthMetrics extends HealthMetrics {
    final List<String> blocked = new ArrayList<>();

    @Override
    public void onAdditionalTagValueCardinalityBlocked(String tagKey) {
      blocked.add(tagKey);
    }
  }
}
