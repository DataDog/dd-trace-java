package com.datadog.openfeature;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.MutableContext;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Runs in a separate Gradle task whose runtime contains OpenFeature SDK 1.17. */
class OpenFeature117CompatibilityTest {
  @Test
  void hooksFallBackWhenHookDataIsUnavailable() {
    assertFalse(HookDataAccessor.isAvailable());

    final HookContext<Object> context =
        HookContext.<Object>builder()
            .flagKey("flag")
            .type(FlagValueType.OBJECT)
            .defaultValue("default")
            .ctx(new MutableContext("subject"))
            .build();
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("flag")
            .value("value")
            .variant("on")
            .build();
    final HookContext<Object> skipped =
        HookContext.<Object>builder()
            .flagKey("flag")
            .type(FlagValueType.OBJECT)
            .defaultValue("default")
            .ctx(context.getCtx())
            .build();
    final Object lifecycle = new Object();
    HookDataAccessor.set(context, "compatibility-test", lifecycle);
    assertNull(HookDataAccessor.get(skipped, "compatibility-test"));
    assertSame(lifecycle, HookDataAccessor.take(context, "compatibility-test"));
    assertNull(HookDataAccessor.get(context, "compatibility-test"));

    final AtomicInteger exposures = new AtomicInteger();
    final ExposureHook exposureHook = new ExposureHook(ignored -> exposures.incrementAndGet());
    assertDoesNotThrow(() -> exposureHook.before(context, emptyMap()));
    assertDoesNotThrow(() -> exposureHook.finallyAfter(skipped, details, emptyMap()));
    assertEquals(0, exposures.get());
    assertDoesNotThrow(() -> exposureHook.finallyAfter(context, details, emptyMap()));
    assertEquals(1, exposures.get());

    final AtomicInteger telemetry = new AtomicInteger();
    final Hook<Object> delegate =
        new Hook<Object>() {
          @Override
          public Optional<EvaluationContext> before(
              final HookContext<Object> hookContext, final Map<String, Object> hints) {
            return Optional.empty();
          }

          @Override
          public void finallyAfter(
              final HookContext<Object> hookContext,
              final FlagEvaluationDetails<Object> evaluation,
              final Map<String, Object> hints) {
            telemetry.incrementAndGet();
          }
        };
    final EvaluationTelemetryOptOutHook optOut = new EvaluationTelemetryOptOutHook(delegate);
    assertDoesNotThrow(() -> optOut.before(context, emptyMap()));
    assertDoesNotThrow(() -> optOut.finallyAfter(context, details, emptyMap()));
    assertEquals(1, telemetry.get());
  }
}
