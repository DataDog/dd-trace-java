package datadog.trace.common.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.core.monitor.HealthMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdditionalTagsCardinalityLimiterTest {

  @Test
  void shortValuePassesThroughLengthCap() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(100, HealthMetrics.NO_OP);
    assertEquals("us-east-1", limiter.applyLengthCap("region", "us-east-1"));
  }

  @Test
  void overLongValueReturnsNullAndFiresHealthMetric() {
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(100, health);
    char[] chars = new char[AdditionalTagsSchema.MAX_ADDITIONAL_TAG_VALUE_LENGTH + 1];
    Arrays.fill(chars, 'x');
    String overlong = new String(chars);

    assertNull(limiter.applyLengthCap("region", overlong));
    assertEquals(1, health.blockedKeys.size());
    assertEquals("region", health.blockedKeys.get(0));
  }

  @Test
  void exactlyMaxLengthPasses() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(100, HealthMetrics.NO_OP);
    char[] chars = new char[AdditionalTagsSchema.MAX_ADDITIONAL_TAG_VALUE_LENGTH];
    Arrays.fill(chars, 'x');
    String exactlyMax = new String(chars);
    assertNotNull(limiter.applyLengthCap("region", exactlyMax));
  }

  @Test
  void counterTracksAdmissionsAndCap() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(3, HealthMetrics.NO_OP);
    assertFalse(limiter.isAtCap());
    limiter.onNewStatEntryAdmitted();
    assertFalse(limiter.isAtCap());
    limiter.onNewStatEntryAdmitted();
    limiter.onNewStatEntryAdmitted();
    assertTrue(limiter.isAtCap());
  }

  @Test
  void resetClearsCounterAndAllowsAdmissionAgain() {
    AdditionalTagsCardinalityLimiter limiter =
        new AdditionalTagsCardinalityLimiter(2, HealthMetrics.NO_OP);
    limiter.onNewStatEntryAdmitted();
    limiter.onNewStatEntryAdmitted();
    assertTrue(limiter.isAtCap());

    limiter.resetBucket();
    assertFalse(limiter.isAtCap());
    limiter.onNewStatEntryAdmitted();
    assertFalse(limiter.isAtCap());
  }

  @Test
  void recordCardinalityBlockFiresHealthMetricPerNonNullValue() {
    RecordingHealthMetrics health = new RecordingHealthMetrics();
    AdditionalTagsCardinalityLimiter limiter = new AdditionalTagsCardinalityLimiter(1, health);
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region", "tenant_id")));
    String[] values = new String[] {"us-east-1", null};

    limiter.recordCardinalityBlock(schema, values);
    assertEquals(1, health.blockedKeys.size());
    assertEquals("region", health.blockedKeys.get(0));
  }

  private static final class RecordingHealthMetrics extends HealthMetrics {
    final List<String> blockedKeys = new ArrayList<>();

    @Override
    public void onAdditionalTagValueCardinalityBlocked(String tagKey) {
      blockedKeys.add(tagKey);
    }
  }
}
