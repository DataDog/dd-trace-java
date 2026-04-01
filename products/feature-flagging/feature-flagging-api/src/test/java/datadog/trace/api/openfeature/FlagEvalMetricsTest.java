package datadog.trace.api.openfeature;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.openfeature.sdk.ErrorCode;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlagEvalMetricsTest {

  @Test
  void recordBasicAttributes() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", "TARGETING_MATCH", null, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.key", "my-flag");
    assertAttribute(attrs, "feature_flag.result.variant", "on");
    assertAttribute(attrs, "feature_flag.result.reason", "targeting_match");
    assertNoAttribute(attrs, "error.type");
    assertNoAttribute(attrs, "feature_flag.result.allocation_key");
  }

  @Test
  void recordErrorAttributes() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("missing-flag", "", "ERROR", ErrorCode.FLAG_NOT_FOUND, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.key", "missing-flag");
    assertAttribute(attrs, "feature_flag.result.variant", "");
    assertAttribute(attrs, "feature_flag.result.reason", "error");
    assertAttribute(attrs, "error.type", "flag_not_found");
    assertNoAttribute(attrs, "feature_flag.result.allocation_key");
  }

  @Test
  void recordTypeMismatchError() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "", "ERROR", ErrorCode.TYPE_MISMATCH, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "error.type", "type_mismatch");
  }

  @Test
  void recordWithAllocationKey() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", "TARGETING_MATCH", null, "default-allocation");

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.result.allocation_key", "default-allocation");
  }

  @Test
  void recordOmitsEmptyAllocationKey() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", "TARGETING_MATCH", null, "");

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertNoAttribute(attrs, "feature_flag.result.allocation_key");
  }

  @Test
  void recordNullVariantBecomesEmptyString() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", null, "DEFAULT", null, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.result.variant", "");
  }

  @Test
  void recordNullReasonBecomesUnknown() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", null, null, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.result.reason", "unknown");
  }

  @Test
  void recordIsNoOpWhenCounterIsNull() {
    FlagEvalMetrics metrics = new FlagEvalMetrics(null);
    // Should not throw
    metrics.record("my-flag", "on", "TARGETING_MATCH", null, null);
  }

  @Test
  void shutdownClearsCounter() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.shutdown();
    metrics.record("my-flag", "on", "TARGETING_MATCH", null, null);

    verifyNoInteractions(counter);
  }

  private static void assertAttribute(Attributes attrs, String key, String expected) {
    String value =
        attrs.asMap().entrySet().stream()
            .filter(e -> e.getKey().getKey().equals(key))
            .map(e -> e.getValue().toString())
            .findFirst()
            .orElse(null);
    if (!expected.equals(value)) {
      throw new AssertionError("Expected attribute " + key + "=" + expected + " but got " + value);
    }
  }

  private static void assertNoAttribute(Attributes attrs, String key) {
    boolean present = attrs.asMap().keySet().stream().anyMatch(k -> k.getKey().equals(key));
    if (present) {
      throw new AssertionError("Expected no attribute " + key + " but it was present");
    }
  }
}
