package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.AdditionalTagsCardinalityLimiter.BLOCKED_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdditionalTagsCardinalityLimiterTest {

  @Test
  void applyLengthCapAdmitsShortValues() {
    AdditionalTagsCardinalityLimiter limiter = newLimiter(100);
    assertEquals("us-east-1", limiter.applyLengthCap("region", "us-east-1"));
  }

  @Test
  void applyLengthCapBlocksLongValues() {
    AdditionalTagsCardinalityLimiter limiter = newLimiter(100);
    String tooLong =
        repeat('x', AdditionalTagsCardinalityLimiter.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 1);
    assertEquals(BLOCKED_VALUE, limiter.applyLengthCap("region", tooLong));
  }

  @Test
  void applyLengthCapFiresHealthMetricOnBlock() {
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(100, health);
    String tooLong =
        repeat('x', AdditionalTagsCardinalityLimiter.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 1);
    limiter.applyLengthCap("region", tooLong);
    limiter.applyLengthCap("region", tooLong);
    assertEquals(2, health.blocked.size());
  }

  @Test
  void isAtCapTracksCounter() {
    AdditionalTagsCardinalityLimiter limiter = newLimiter(3);
    assertFalse(limiter.isAtCap());
    limiter.onNewStatEntryAdmitted();
    limiter.onNewStatEntryAdmitted();
    assertFalse(limiter.isAtCap());
    limiter.onNewStatEntryAdmitted();
    assertTrue(limiter.isAtCap());
  }

  @Test
  void resetBucketClearsCounter() {
    AdditionalTagsCardinalityLimiter limiter = newLimiter(2);
    limiter.onNewStatEntryAdmitted();
    limiter.onNewStatEntryAdmitted();
    assertTrue(limiter.isAtCap());
    limiter.resetBucket();
    assertFalse(limiter.isAtCap());
  }

  @Test
  void resetBucketRearmsLengthWarnFlag() {
    // We can't directly observe the warn flag, but resetBucket() should not throw and the next
    // long value should still be considered a block (health metric fires per call regardless).
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(100, health);
    String tooLong =
        repeat('x', AdditionalTagsCardinalityLimiter.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 1);
    limiter.applyLengthCap("region", tooLong);
    limiter.resetBucket();
    limiter.applyLengthCap("region", tooLong);
    assertEquals(2, health.blocked.size());
  }

  private static AdditionalTagsCardinalityLimiter newLimiter(int maxStatEntries) {
    return new AdditionalTagsCardinalityLimiter(maxStatEntries, HealthMetrics.NO_OP);
  }

  private static String repeat(char c, int len) {
    char[] chars = new char[len];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  private static final class RecordingHealthMetrics extends HealthMetrics {
    final List<String> blocked = new ArrayList<>();

    @Override
    public void onAdditionalTagValueCardinalityBlocked(String tagKey) {
      blocked.add(tagKey);
    }
  }
}
