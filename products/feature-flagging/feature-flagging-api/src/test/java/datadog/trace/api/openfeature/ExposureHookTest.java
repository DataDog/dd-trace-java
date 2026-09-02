package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExposureHookTest {

  private static final long EVALUATION_TIMESTAMP = 1_700_000_000_000L;

  private final List<ExposureEvent> captured = new ArrayList<>();
  private final FeatureFlaggingGateway.ExposureListener listener = captured::add;

  @BeforeEach
  void register() {
    FeatureFlaggingGateway.addExposureListener(listener);
  }

  @AfterEach
  void deregister() {
    FeatureFlaggingGateway.removeExposureListener(listener);
  }

  @Test
  void afterDispatchesExposureWhenDoLogIsTrue() {
    final MutableContext evaluationContext = new MutableContext("user-1");
    evaluationContext.add("region", "us-east-1");

    ExposureHook.INSTANCE.after(
        hookContext(evaluationContext), details(true), Collections.emptyMap());

    assertEquals(1, captured.size());
    final ExposureEvent exposure = captured.get(0);
    assertEquals(EVALUATION_TIMESTAMP, exposure.timestamp);
    assertEquals("allocation-1", exposure.allocation.key);
    assertEquals("my-flag", exposure.flag.key);
    assertEquals("on", exposure.variant.key);
    assertEquals("user-1", exposure.subject.id);
    assertEquals("us-east-1", exposure.subject.attributes.get("region"));
  }

  @Test
  void afterDoesNotDispatchExposureWhenDoLogIsFalse() {
    ExposureHook.INSTANCE.after(
        hookContext(new MutableContext("user-1")), details(false), Collections.emptyMap());

    assertTrue(captured.isEmpty());
  }

  @Test
  void afterDoesNotDispatchExposureWithoutDatadogMetadata() {
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.builder().flagKey("my-flag").value("value").variant("on").build();

    ExposureHook.INSTANCE.after(
        hookContext(new MutableContext("user-1")), details, Collections.emptyMap());

    assertTrue(captured.isEmpty());
  }

  @Test
  void afterHandlesNullDetails() {
    ExposureHook.INSTANCE.after(null, null, null);

    assertTrue(captured.isEmpty());
  }

  private static HookContext<Object> hookContext(final MutableContext evaluationContext) {
    return HookContext.<Object>builder()
        .flagKey("my-flag")
        .type(FlagValueType.STRING)
        .defaultValue("default")
        .ctx(evaluationContext)
        .build();
  }

  private static FlagEvaluationDetails<Object> details(final boolean doLog) {
    return FlagEvaluationDetails.builder()
        .flagKey("my-flag")
        .value("value")
        .variant("on")
        .flagMetadata(
            ImmutableMetadata.builder()
                .addString("allocationKey", "allocation-1")
                .addBoolean(DDEvaluator.METADATA_DO_LOG, doLog)
                .addLong(DDEvaluator.METADATA_EVAL_TIMESTAMP_MS, EVALUATION_TIMESTAMP)
                .build())
        .build();
  }
}
