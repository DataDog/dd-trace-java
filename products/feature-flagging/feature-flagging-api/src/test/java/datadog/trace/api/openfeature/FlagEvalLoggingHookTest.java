package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.Reason;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FlagEvalLoggingHook}: cheap capture, non-blocking enqueue, eval-time
 * metadata, absent-variant detection, and killswitch-via-writer-null behaviour.
 */
class FlagEvalLoggingHookTest {

  // ---- helpers ----

  /**
   * Creates a writer that captures the enqueued event for assertion. Uses an anonymous class since
   * FlagEvaluationWriter has multiple abstract methods.
   */
  private FlagEvaluationWriter capturingWriter(final AtomicReference<FlagEvalEvent> ref) {
    return new FlagEvaluationWriter() {
      @Override
      public void enqueue(final FlagEvalEvent event) {
        ref.set(event);
      }

      @Override
      public void start() {}

      @Override
      public void close() {}
    };
  }

  private static FlagEvalLoggingHook<Object> hookWithWriter(final FlagEvaluationWriter writer) {
    return new FlagEvalLoggingHook<>(writer);
  }

  private static FlagEvaluationDetails<Object> details(
      final String flagKey,
      final Object value,
      final String variant,
      final String reason,
      final ImmutableMetadata metadata) {
    final FlagEvaluationDetails.FlagEvaluationDetailsBuilder<Object> builder =
        FlagEvaluationDetails.<Object>builder().flagKey(flagKey).value(value).reason(reason);
    if (variant != null) {
      builder.variant(variant);
    }
    if (metadata != null) {
      builder.flagMetadata(metadata);
    }
    return builder.build();
  }

  private static HookContext<Object> hookCtxWithTargetingKey(
      final String flagKey, final String targetingKey) {
    final MutableContext ctx = new MutableContext(targetingKey);
    return HookContext.<Object>builder()
        .flagKey(flagKey)
        .type(FlagValueType.STRING)
        .defaultValue("default")
        .ctx(ctx)
        .build();
  }

  // ---- test: hook calls writer.enqueue once with flagKey, variant, allocationKey ----

  @Test
  void finallyAfterEnqueuesEventWithAllBasicFields() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final FlagEvaluationDetails<Object> det =
        details(
            "my-flag",
            "on-value",
            "on",
            Reason.TARGETING_MATCH.name(),
            ImmutableMetadata.builder().addString("allocationKey", "alloc-1").build());

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get(), "writer.enqueue must be called once");
    final FlagEvalEvent e = captured.get();
    assertEquals("my-flag", e.flagKey);
    assertEquals("on", e.variant, "variant must be the OpenFeature variant key");
    assertEquals("alloc-1", e.allocationKey);
  }

  // ---- variant comes from details.getVariant(), NOT details.getValue() ----

  @Test
  void variantIsTheVariantKeyNotTheEvaluatedValue() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    // value and variant DIFFER, so a value-vs-variant mistake is detectable.
    final FlagEvaluationDetails<Object> det =
        details(
            "g1-flag",
            "the-evaluated-value", // value
            "the-variant-key", // variant
            Reason.TARGETING_MATCH.name(),
            null);

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertEquals(
        "the-variant-key",
        captured.get().variant,
        "variant must be sourced from details.getVariant(), not details.getValue()");
  }

  // ---- test: evalTimeMs from metadata "dd.eval.timestamp_ms" ----

  @Test
  void evalTimeMsComesFromMetadataWhenPresent() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final long expectedTimestamp = 1_700_000_000_000L;
    final FlagEvaluationDetails<Object> det =
        details(
            "ts-flag",
            "v",
            "v",
            Reason.SPLIT.name(),
            ImmutableMetadata.builder()
                .addString("allocationKey", "a")
                .addLong("dd.eval.timestamp_ms", expectedTimestamp)
                .build());

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertEquals(
        expectedTimestamp,
        captured.get().evalTimeMs,
        "evalTimeMs must come from dd.eval.timestamp_ms metadata when present");
  }

  // ---- test: evalTimeMs falls back to System.currentTimeMillis() when absent ----

  @Test
  void evalTimeMsFallsBackToCurrentTimeWhenMetadataAbsent() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final long before = System.currentTimeMillis();
    final FlagEvaluationDetails<Object> det =
        details("ts-flag", "v", "v", Reason.SPLIT.name(), null);

    hook.finallyAfter(null, det, Collections.emptyMap());

    final long after = System.currentTimeMillis();
    assertNotNull(captured.get());
    final long ts = captured.get().evalTimeMs;
    assertTrue(
        ts >= before && ts <= after,
        "evalTimeMs must fall back to hook-fire time when metadata absent. got: " + ts);
  }

  // ---- test: absent variant -> variant is null -> runtime default ----

  @Test
  void absentVariantProducesNullVariant() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    // A runtime default returns the default value but no variant.
    final FlagEvaluationDetails<Object> det =
        details("def-flag", "default-value", null, Reason.DEFAULT.name(), null);

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertNull(captured.get().variant, "Absent variant must stay null (runtime default)");
  }

  // ---- test: error message captured from details (error object support) ----

  @Test
  void errorMessageCapturedFromDetails() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final FlagEvaluationDetails<Object> det =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("err-flag")
            .value("default")
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.TYPE_MISMATCH)
            .errorMessage("value does not match declared type")
            .build();

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertEquals(
        "value does not match declared type",
        captured.get().errorMessage,
        "errorMessage must be captured from the evaluation details");
  }

  // ---- test: error code used as fallback message when error message is empty ----

  @Test
  void errorCodeUsedAsFallbackWhenMessageEmpty() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final FlagEvaluationDetails<Object> det =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("err-flag")
            .value("default")
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.FLAG_NOT_FOUND)
            .build();

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertEquals(
        "FLAG_NOT_FOUND",
        captured.get().errorMessage,
        "error code name must be used when no error message is present");
  }

  // ---- test: success path has no error message ----

  @Test
  void successPathHasNullErrorMessage() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final FlagEvaluationDetails<Object> det =
        details("ok-flag", "v", "v", Reason.TARGETING_MATCH.name(), null);

    hook.finallyAfter(null, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertNull(captured.get().errorMessage, "success path must have no error message");
  }

  // ---- test: hook does NO aggregation on the hook thread ----

  @Test
  void finallyAfterOnlyCallsEnqueueNoOtherWriterMethods() {
    final FlagEvaluationWriter writer = mock(FlagEvaluationWriter.class);
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(writer);

    final FlagEvaluationDetails<Object> det =
        details("flag", "v", "v", Reason.TARGETING_MATCH.name(), null);

    hook.finallyAfter(null, det, Collections.emptyMap());

    // Exactly one enqueue call, no start/close/aggregate
    verify(writer, times(1)).enqueue(any(FlagEvalEvent.class));
    verify(writer, never()).close();
    verify(writer, never()).start();
  }

  // ---- test: writer=null -> no-op (killswitch off / not yet started) ----

  @Test
  void writerNullIsNoOp() {
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(null);
    final FlagEvaluationDetails<Object> det =
        details("flag", "v", "v", Reason.TARGETING_MATCH.name(), null);

    // Must not throw; nothing is enqueued
    hook.finallyAfter(null, det, Collections.emptyMap());
  }

  @Test
  void writerLookupLinkageErrorIsNoOp() {
    final FlagEvalLoggingHook<Object> hook =
        new FlagEvalLoggingHook<>(
            () -> {
              throw new NoSuchMethodError("old bootstrap");
            });
    final FlagEvaluationDetails<Object> det =
        details("flag", "v", "v", Reason.TARGETING_MATCH.name(), null);

    assertDoesNotThrow(() -> hook.finallyAfter(null, det, Collections.emptyMap()));
  }

  // ---- test: details=null -> no-op ----

  @Test
  void detailsNullIsNoOp() {
    final FlagEvaluationWriter writer = mock(FlagEvaluationWriter.class);
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(writer);

    // Should not throw
    hook.finallyAfter(null, null, Collections.emptyMap());

    verifyNoInteractions(writer);
  }

  // ---- test: targetingKey extracted from evaluation context ----

  @Test
  void targetingKeyExtractedFromContext() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final FlagEvaluationDetails<Object> det =
        details("ctx-flag", "v", "v", Reason.SPLIT.name(), null);

    final HookContext<Object> hookCtx = hookCtxWithTargetingKey("ctx-flag", "user-42");

    hook.finallyAfter(hookCtx, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertEquals(
        "user-42",
        captured.get().targetingKey,
        "targetingKey must be extracted from the evaluation context");
  }

  @Test
  void contextAttributesAreFlattenedAndConvertedAfterEnqueue() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final Map<String, Object> profile = new HashMap<>();
    profile.put("tier", "gold");
    final Map<String, Object> attributes = new HashMap<>();
    attributes.put("score", 42);
    attributes.put("profile", profile);
    final MutableContext context =
        new MutableContext(Value.objectToValue(attributes).asStructure().asMap());
    context.setTargetingKey("user-42");

    final HookContext<Object> hookCtx =
        HookContext.<Object>builder()
            .flagKey("ctx-flag")
            .type(FlagValueType.STRING)
            .defaultValue("default")
            .ctx(context)
            .build();
    final FlagEvaluationDetails<Object> det =
        details("ctx-flag", "v", "v", Reason.TARGETING_MATCH.name(), null);

    hook.finallyAfter(hookCtx, det, Collections.emptyMap());

    assertNotNull(captured.get());
    assertTrue(captured.get().attrs.isEmpty(), "hook must not flatten context before enqueue");
    final Map<String, Object> attrs = captured.get().contextAttributes();
    assertEquals(42, attrs.get("score"));
    assertEquals("gold", attrs.get("profile.tier"));
    assertFalse(attrs.containsKey("targetingKey"));
    assertTrue(
        attrs.values().stream().noneMatch(Value.class::isInstance),
        "context attrs must contain converted scalar values, not OpenFeature Value wrappers");
  }

  @Test
  void contextAttributesUseEnqueueTimeSnapshot() {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    final FlagEvalLoggingHook<Object> hook = hookWithWriter(capturingWriter(captured));

    final MutableContext context = new MutableContext("user-42");
    context.add("region", "us-east-1");
    final MutableStructure profile = new MutableStructure();
    profile.add("tier", "gold");
    context.add("profile", profile);
    final List<Value> cohorts = new ArrayList<>();
    cohorts.add(Value.objectToValue("beta"));
    context.add("cohorts", cohorts);

    final HookContext<Object> hookCtx =
        HookContext.<Object>builder()
            .flagKey("ctx-flag")
            .type(FlagValueType.STRING)
            .defaultValue("default")
            .ctx(context)
            .build();
    final FlagEvaluationDetails<Object> det =
        details("ctx-flag", "v", "v", Reason.TARGETING_MATCH.name(), null);

    hook.finallyAfter(hookCtx, det, Collections.emptyMap());
    context.add("region", "eu-west-1");
    context.add("late", "ignored");
    profile.add("tier", "platinum");
    profile.add("late", "ignored");
    cohorts.set(0, Value.objectToValue("ga"));
    cohorts.add(Value.objectToValue("late"));

    assertNotNull(captured.get());
    final Map<String, Object> attrs = captured.get().contextAttributes();
    assertEquals("us-east-1", attrs.get("region"));
    assertEquals("gold", attrs.get("profile.tier"));
    assertEquals("beta", attrs.get("cohorts[0]"));
    assertFalse(attrs.containsKey("late"));
    assertFalse(attrs.containsKey("profile.late"));
    assertFalse(attrs.containsKey("cohorts[1]"));
  }
}
