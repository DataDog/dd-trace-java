package com.datadog.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.ConditionOperator;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Runs in a separate Gradle task whose runtime contains OpenFeature SDK 1.17. */
class OpenFeature117CompatibilityTest {
  @Test
  void legacyHookIgnoresIncompleteFinallyCalls() {
    assertFalse(HookDataAccessor.isAvailable());
    final List<String> callbacks = new ArrayList<>();
    final ExposureHook hook =
        new ExposureHook(evaluation -> callbacks.add(evaluation.getFlagKey()));
    final HookContext<Object> context =
        HookContext.<Object>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(new MutableContext("subject"))
            .build();
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder().flagKey("root").value(1).build();

    hook.finallyAfter(null, details, emptyMap());
    hook.finallyAfter(context, null, emptyMap());

    assertEquals(0, callbacks.size());
  }

  @Test
  void realClientLifecycleCompletesWithoutHookData() throws Exception {
    assertFalse(HookDataAccessor.isAvailable());
    final List<String> callbacks = new ArrayList<>();
    final List<String> exposures = new ArrayList<>();
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache();
    final DDEvaluator evaluator =
        new DDEvaluator(() -> {}, AgentBridge.NONE, Settings.load(AgentBridge.NONE)) {
          @Override
          public boolean initialize(
              final long timeout, final TimeUnit unit, final EvaluationContext context) {
            return hasConfiguration();
          }

          @Override
          ExposureDeduplicationCache exposureCache() {
            return cache;
          }

          @Override
          boolean isExposureDeliveryEnabled() {
            return true;
          }

          @Override
          void recordExposure(final ExposureEvent event) {
            exposures.add(event.flag.key);
          }
        };
    evaluator.accept(dependentConfiguration());
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.addHooks(new ExposureHook(evaluation -> callbacks.add(evaluation.getFlagKey())));
    api.setProviderAndWait(new Provider(new Provider.Options(), evaluator, false));
    try {
      final Client client = api.getClient();
      for (int i = 0; i < 100; i++) {
        assertEquals(
            1, client.getIntegerDetails("root", 0, new MutableContext("s" + i)).getValue());
      }
    } finally {
      api.clearHooks();
      api.shutdown();
    }

    assertEquals(100, callbacks.size(), "one root callback per evaluation");
    assertEquals(200, exposures.size(), "dependency and root exposure per evaluation");
    assertNull(evaluator.legacyLifecycleScope("root"), "no dependency scope remains");
  }

  private static ServerConfiguration dependentConfiguration() {
    final Flag dependency =
        new Flag(
            "dependency",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(
                new Allocation(
                    "dependency-allocation",
                    null,
                    null,
                    null,
                    singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
                    true)));
    final Rule rule =
        new Rule(
            singletonList(
                new ConditionConfiguration(
                    ConditionOperator.ONE_OF, null, "dependency", singletonList("on"))));
    final Flag root =
        new Flag(
            "root",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(
                new Allocation(
                    "root-allocation",
                    singletonList(rule),
                    null,
                    null,
                    singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
                    true)));
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", dependency);
    flags.put("root", root);
    return new ServerConfiguration("", "", false, null, flags);
  }
}
