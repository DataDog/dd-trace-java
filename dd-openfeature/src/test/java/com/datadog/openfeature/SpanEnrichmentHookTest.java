package com.datadog.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.connector.SpanEnricher;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.Value;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Capture-side unit suite for APM feature-flag span enrichment. The hook records evaluations to the
 * {@link SpanEnricher} and the agent does the accumulation, so these tests assert the recorded
 * events (not span tags) plus the {@link Provider} gating.
 */
class SpanEnrichmentHookTest {

  private final List<SpanEnrichmentEvent> captured = new ArrayList<>();
  private final SpanEnricher enricher =
      new SpanEnricher() {
        @Override
        public void serialId(final int serialId, final boolean doLog, final String targetingKey) {
          SpanEnrichmentHookTest.this.captured.add(
              new SpanEnrichmentEvent(true, serialId, doLog, targetingKey, null, null));
        }

        @Override
        public void runtimeDefault(final String flagKey, final Object defaultValue) {
          SpanEnrichmentHookTest.this.captured.add(
              new SpanEnrichmentEvent(false, 0, false, null, flagKey, defaultValue));
        }
      };

  // ---- helpers ----

  private static FlagEvaluationDetails<Object> details(
      final String flagKey,
      final String variant,
      final Object value,
      final ImmutableMetadata metadata) {
    return FlagEvaluationDetails.builder()
        .flagKey(flagKey)
        .variant(variant)
        .value(value)
        .flagMetadata(metadata)
        .build();
  }

  private static ImmutableMetadata metadata(final Integer serialId, final boolean doLog) {
    final ImmutableMetadata.ImmutableMetadataBuilder builder = ImmutableMetadata.builder();
    if (serialId != null) {
      builder.addInteger(SpanEnrichmentHook.METADATA_SERIAL_ID, serialId);
    }
    builder.addBoolean(SpanEnrichmentHook.METADATA_DO_LOG, doLog);
    return builder.build();
  }

  private static HookContext<Object> ctx(final String flagKey, final String targetingKey) {
    return HookContext.from(
        flagKey,
        FlagValueType.STRING,
        null,
        null,
        targetingKey == null ? new ImmutableContext() : new ImmutableContext(targetingKey),
        "default");
  }

  // ---- serial-id branch ----

  @Test
  void serialIdWithDoLogDispatchesSerialAndSubject() {
    new SpanEnrichmentHook(this.enricher)
        .finallyAfter(
            ctx("flag", "user-1"),
            details("flag", "on", "v", metadata(42, true)),
            Collections.emptyMap());

    assertEquals(1, captured.size());
    final SpanEnrichmentEvent event = captured.get(0);
    assertTrue(event.hasSerialId());
    assertEquals(42, event.serialId());
    assertTrue(event.doLog());
    assertEquals("user-1", event.targetingKey());
  }

  @Test
  void serialIdWithoutDoLogStillDispatchesSerialButNotDoLog() {
    new SpanEnrichmentHook(this.enricher)
        .finallyAfter(
            ctx("flag", "user-1"),
            details("flag", "on", "v", metadata(7, false)),
            Collections.emptyMap());

    assertEquals(1, captured.size());
    final SpanEnrichmentEvent event = captured.get(0);
    assertTrue(event.hasSerialId());
    assertEquals(7, event.serialId());
    assertFalse(
        event.doLog(), "doLog=false must be carried through so the write side skips subject");
  }

  @Test
  void wrongTypedSerialIdDispatchesNothing() {
    // Defensive: a non-integer value under the serial-id key (wrong type) is ignored, not crashed.
    final ImmutableMetadata bad =
        ImmutableMetadata.builder()
            .addString(SpanEnrichmentHook.METADATA_SERIAL_ID, "not-a-number")
            .addBoolean(SpanEnrichmentHook.METADATA_DO_LOG, true)
            .build();
    new SpanEnrichmentHook(this.enricher)
        .finallyAfter(
            ctx("flag", "user-1"), details("flag", "on", "v", bad), Collections.emptyMap());
    assertTrue(captured.isEmpty(), "a wrong-typed serial id must never break eval or dispatch");
  }

  // ---- runtime-default branch (missing variant) ----

  @Test
  void missingVariantDispatchesRuntimeDefaultWithNativeMap() {
    final Map<String, Object> objectValue = Collections.singletonMap("k", "val");
    new SpanEnrichmentHook(this.enricher)
        .finallyAfter(
            ctx("obj-flag", "user-1"),
            details("obj-flag", null, objectValue, ImmutableMetadata.builder().build()),
            Collections.emptyMap());

    assertEquals(1, captured.size());
    final SpanEnrichmentEvent event = captured.get(0);
    assertFalse(event.hasSerialId());
    assertEquals("obj-flag", event.flagKey());
    assertEquals(
        objectValue, event.defaultValue(), "a native map default passes through unchanged");
  }

  @Test
  void missingVariantUnwrapsOpenFeatureValueStructureToNativeMap() {
    final Map<String, Value> inner = new LinkedHashMap<>();
    inner.put("enabled", new Value(true));
    final Value structureDefault = new Value(new ImmutableStructure(inner));

    new SpanEnrichmentHook(this.enricher)
        .finallyAfter(
            ctx("struct-flag", "user-1"),
            details("struct-flag", null, structureDefault, ImmutableMetadata.builder().build()),
            Collections.emptyMap());

    assertEquals(1, captured.size());
    final Object value = captured.get(0).defaultValue();
    // Crucially a native Map, NOT an OpenFeature Value — the seam carries only JDK types.
    final Map<?, ?> asMap = assertInstanceOf(Map.class, value);
    assertEquals(Boolean.TRUE, asMap.get("enabled"));
  }

  // ---- unwrapDefaultValue (the Value -> native conversion) ----

  @Test
  void unwrapConvertsValueScalarsToNative() {
    assertEquals("hello", SpanEnrichmentHook.unwrapDefaultValue(new Value("hello")));
    assertEquals(Boolean.TRUE, SpanEnrichmentHook.unwrapDefaultValue(new Value(true)));
    assertEquals(7, SpanEnrichmentHook.unwrapDefaultValue(new Value(7)));
    assertNull(SpanEnrichmentHook.unwrapDefaultValue(new Value()));
  }

  @Test
  void unwrapConvertsValueListToNativeList() {
    final Value listDefault =
        new Value(Arrays.asList(new Value("a"), new Value(2), new Value(true)));
    final Object out = SpanEnrichmentHook.unwrapDefaultValue(listDefault);
    assertEquals(Arrays.asList("a", 2, Boolean.TRUE), out);
  }

  @Test
  void unwrapPassesNativeValuesThrough() {
    final Map<String, Object> native0 = Collections.singletonMap("a", "b");
    assertEquals(native0, SpanEnrichmentHook.unwrapDefaultValue(native0));
    assertEquals("plain", SpanEnrichmentHook.unwrapDefaultValue("plain"));
    assertNull(SpanEnrichmentHook.unwrapDefaultValue(null));
  }

  // ---- error isolation ----

  @Test
  void nullDetailsDispatchesNothing() {
    new SpanEnrichmentHook(this.enricher).finallyAfter(null, null, null);
    assertTrue(captured.isEmpty());
  }

  // ---- Provider gating ----

  @Test
  void gateOffConstructsNoHook() {
    final Provider provider = new Provider(new Provider.Options(), null, Boolean.FALSE);
    assertNull(provider.spanEnrichmentHook(), "gate off => no span-enrichment hook");
    for (final Hook hook : provider.getProviderHooks()) {
      assertFalse(
          hook instanceof SpanEnrichmentHook, "gate off => SpanEnrichmentHook never registered");
    }
  }

  @Test
  void gateOnConstructsHookAndRegistersItInProviderHooks() {
    final Provider provider = new Provider(new Provider.Options(), null, Boolean.TRUE);
    assertTrue(provider.spanEnrichmentHook() != null, "gate on => hook constructed");
    boolean registered = false;
    for (final Hook hook : provider.getProviderHooks()) {
      if (hook instanceof SpanEnrichmentHook) {
        registered = true;
      }
    }
    assertTrue(registered, "gate on => SpanEnrichmentHook registered in getProviderHooks");
  }

  @Test
  void getProviderHooksReturnsSameInstanceEachCall() {
    final Provider gateOff = new Provider(new Provider.Options(), null, Boolean.FALSE);
    assertTrue(
        gateOff.getProviderHooks() == gateOff.getProviderHooks(),
        "gate off => getProviderHooks allocates nothing (same instance)");

    final Provider gateOn = new Provider(new Provider.Options(), null, Boolean.TRUE);
    assertTrue(
        gateOn.getProviderHooks() == gateOn.getProviderHooks(),
        "gate on => getProviderHooks allocates nothing (same instance)");
  }

  /** A recorded span enrichment. */
  private static final class SpanEnrichmentEvent {
    private final boolean serialIdPresent;
    private final int serialId;
    private final boolean doLog;
    private final String targetingKey;
    private final String flagKey;
    private final Object defaultValue;

    SpanEnrichmentEvent(
        final boolean serialIdPresent,
        final int serialId,
        final boolean doLog,
        final String targetingKey,
        final String flagKey,
        final Object defaultValue) {
      this.serialIdPresent = serialIdPresent;
      this.serialId = serialId;
      this.doLog = doLog;
      this.targetingKey = targetingKey;
      this.flagKey = flagKey;
      this.defaultValue = defaultValue;
    }

    boolean hasSerialId() {
      return this.serialIdPresent;
    }

    int serialId() {
      return this.serialId;
    }

    boolean doLog() {
      return this.doLog;
    }

    String targetingKey() {
      return this.targetingKey;
    }

    String flagKey() {
      return this.flagKey;
    }

    Object defaultValue() {
      return this.defaultValue;
    }
  }
}
