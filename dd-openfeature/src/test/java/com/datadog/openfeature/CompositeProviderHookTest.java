package com.datadog.openfeature;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.HookData;
import dev.openfeature.sdk.MutableContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompositeProviderHookTest {

  private final List<String> calls = new ArrayList<>();

  @Test
  void registersNothingWithoutHooks() {
    assertTrue(new CompositeProviderHook.Builder().build().isEmpty());
  }

  @Test
  void registersOneHookThatRunsTheHooksInOrder() {
    final List<Hook> registered =
        new CompositeProviderHook.Builder()
            .addTelemetry(new Recording("a"))
            .add(new Recording("b"))
            .addTelemetry(new Recording("c"))
            .build();

    assertEquals(1, registered.size());
    finallyAfter(registered.get(0), hookContext(new MutableContext("user-1")));
    assertEquals(asList("a", "b", "c"), this.calls);
  }

  @Test
  void aHookThatThrowsDoesNotStopTheOthers() {
    final Hook<Object> failing =
        new Hook<Object>() {
          @Override
          public void finallyAfter(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            CompositeProviderHookTest.this.calls.add("failing");
            throw new IllegalStateException("hook failure");
          }
        };
    final Hook composite =
        new CompositeProviderHook.Builder()
            .add(new Recording("a"))
            .add(failing)
            .add(new Recording("b"))
            .build()
            .get(0);

    assertDoesNotThrow(() -> finallyAfter(composite, hookContext(new MutableContext("user-1"))));
    assertEquals(asList("a", "failing", "b"), this.calls);
  }

  @Test
  void skipsAHookForAFlagTypeItDoesNotSupport() {
    final Hook<Object> booleansOnly =
        new Recording("booleans") {
          @Override
          public boolean supportsFlagValueType(final FlagValueType flagValueType) {
            return flagValueType == FlagValueType.BOOLEAN;
          }
        };
    final Hook composite =
        new CompositeProviderHook.Builder()
            .add(booleansOnly)
            .add(new Recording("all"))
            .build()
            .get(0);

    finallyAfter(composite, hookContext(new MutableContext("user-1")));
    assertEquals(asList("all"), this.calls);
  }

  @Test
  void theOptOutSkipsOnlyTelemetryHooks() {
    final Hook composite = optOutComposite();
    final MutableContext context = new MutableContext("user-1");
    context.add(CompositeProviderHook.OPT_OUT_PARAMETER, true);

    finallyAfter(composite, hookContext(context));
    assertEquals(asList("exposure"), this.calls);
  }

  @Test
  void theOptOutIsIgnoredUnlessEnabled() {
    final Hook composite =
        new CompositeProviderHook.Builder()
            .addTelemetry(new Recording("telemetry"))
            .add(new Recording("exposure"))
            .build()
            .get(0);
    final MutableContext context = new MutableContext("user-1");
    context.add(CompositeProviderHook.OPT_OUT_PARAMETER, true);

    finallyAfter(composite, hookContext(context));
    assertEquals(asList("telemetry", "exposure"), this.calls);
  }

  @Test
  @SuppressWarnings("unchecked")
  void keepsTheDecisionTakenBeforeTheContextChanges() {
    final Hook composite = optOutComposite();
    final MutableContext context = new MutableContext("user-1");
    context.add(CompositeProviderHook.OPT_OUT_PARAMETER, true);
    final HookData data = new MapHookData();

    composite.before(hookContext(context, data), emptyMap());
    // An application hook removes the parameter after the provider's before hook has run.
    final HookContext<Object> later = hookContext(new MutableContext("user-1"), data);
    finallyAfter(composite, later);
    assertEquals(asList("exposure"), this.calls);
  }

  @Test
  @SuppressWarnings("unchecked")
  void decidesAtTheErrorStageWhenTheBeforeStageDidNotRun() {
    final Hook composite = optOutComposite();
    final MutableContext context = new MutableContext("user-1");
    context.add(CompositeProviderHook.OPT_OUT_PARAMETER, true);
    final HookData data = new MapHookData();

    composite.error(hookContext(context, data), new RuntimeException("before failed"), emptyMap());
    finallyAfter(composite, hookContext(new MutableContext("user-1"), data));
    assertEquals(asList("exposure"), this.calls);
  }

  @Test
  void exposesTheHooksItRuns() {
    final Recording a = new Recording("a");
    final Recording b = new Recording("b");
    final CompositeProviderHook composite =
        (CompositeProviderHook)
            new CompositeProviderHook.Builder().addTelemetry(a).add(b).build().get(0);

    assertEquals(2, composite.hooks().size());
    assertSame(a, composite.hooks().get(0));
    assertSame(b, composite.hooks().get(1));
    assertEquals("CompositeProviderHook[Recording, Recording]", composite.toString());
  }

  private Hook optOutComposite() {
    return new CompositeProviderHook.Builder()
        .addTelemetry(new Recording("telemetry"))
        .add(new Recording("exposure"))
        .optOutEnabled(true)
        .build()
        .get(0);
  }

  @SuppressWarnings("unchecked")
  private static void finallyAfter(final Hook hook, final HookContext<Object> context) {
    hook.finallyAfter(
        context, FlagEvaluationDetails.<Object>builder().flagKey("flag").build(), emptyMap());
  }

  private static HookContext<Object> hookContext(final MutableContext context) {
    return hookContext(context, new MapHookData());
  }

  private static HookContext<Object> hookContext(
      final MutableContext context, final HookData data) {
    return HookContext.<Object>builder()
        .flagKey("flag")
        .type(FlagValueType.STRING)
        .defaultValue("default")
        .ctx(context)
        .hookData(data)
        .build();
  }

  private class Recording implements Hook<Object> {
    private final String name;

    Recording(final String name) {
      this.name = name;
    }

    @Override
    public void finallyAfter(
        final HookContext<Object> context,
        final FlagEvaluationDetails<Object> details,
        final Map<String, Object> hints) {
      CompositeProviderHookTest.this.calls.add(this.name);
    }
  }

  private static final class MapHookData implements HookData {
    private final Map<String, Object> values = new HashMap<>();

    @Override
    public void set(final String key, final Object value) {
      this.values.put(key, value);
    }

    @Override
    public Object get(final String key) {
      return this.values.get(key);
    }

    @Override
    public <T> T get(final String key, final Class<T> type) {
      return type.cast(this.values.get(key));
    }
  }
}
