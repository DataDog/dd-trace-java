package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.Feature;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import datadog.trace.api.openfeature.Provider.Options;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExposureHookTest {

  private final List<ExposureEvent> sentToDatadog = new ArrayList<>();
  private final FeatureFlaggingGateway.ExposureListener listener = sentToDatadog::add;
  private final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
  private Client client;

  @BeforeEach
  void setUp() {
    ExposureDeduplicationCache.INSTANCE.clear();
    FeatureFlaggingGateway.addExposureListener(listener);
  }

  @AfterEach
  void tearDown() {
    FeatureFlaggingGateway.removeExposureListener(listener);
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    ExposureDeduplicationCache.INSTANCE.clear();
  }

  @Test
  void shouldSendMatchesTheExposuresSentToDatadog() throws Exception {
    start(true, configuration(Boolean.TRUE, null));

    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertEquals(Arrays.asList(true, false, true), shouldSend());
    assertEquals(2, sentToDatadog.size());
    assertEquals("user-1", sentToDatadog.get(0).subject.id);
    assertEquals("user-2", sentToDatadog.get(1).subject.id);
  }

  @Test
  void customerHookStillReceivesExposuresWhenDatadogLoggingIsOff() throws Exception {
    start(false, configuration(Boolean.TRUE, null));

    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertTrue(sentToDatadog.isEmpty());
    assertEquals(Arrays.asList(true, false, true), shouldSend());
  }

  @Test
  void aFailedEvaluationIsNotAnExposure() throws Exception {
    start(true, configuration(Boolean.TRUE, null));
    OpenFeatureAPI.getInstance()
        .addHooks(
            new Hook<Object>() {
              @Override
              public void after(
                  final HookContext<Object> ctx,
                  final FlagEvaluationDetails<Object> details,
                  final Map<String, Object> hints) {
                throw new IllegalStateException("application validation failed");
              }
            });

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    assertEquals(ErrorCode.GENERAL, details.getErrorCode());
    final ExposureHook.Evaluation evaluation = evaluations.get(0);
    assertFalse(evaluation.isExposure());
    assertFalse(evaluation.isCacheHit());
    assertFalse(evaluation.shouldSend());
  }

  @Test
  void anAllocationThatDoesNotLogExposuresIsNotAnExposure() throws Exception {
    start(true, configuration(Boolean.FALSE, null));

    evaluate("user-1");

    final ExposureHook.Evaluation evaluation = evaluations.get(0);
    assertFalse(evaluation.isExposure());
    assertFalse(evaluation.isCacheHit());
    assertFalse(evaluation.shouldSend());
    assertEquals("flag", evaluation.getDetails().getFlagKey());
    assertEquals("user-1", evaluation.getContext().getTargetingKey());
  }

  @Test
  void exposesTheFeaturesOfTheSelectedSplit() throws Exception {
    start(
        true,
        configuration(
            Boolean.TRUE,
            Arrays.asList(
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("holdout.assignment_group", "status_quo", singletonList("HOOK")),
                new Feature("holdout.weight", 0.5, Arrays.asList("EVALUATION", "HOOK")),
                new Feature(
                    "holdout.should_include_in_holdout_analysis",
                    true,
                    Arrays.asList("HOOK", "SOME_FUTURE_DESTINATION")),
                new Feature("bandit.policy_id", "p2", Arrays.asList("EXPOSURE", "EVALUATION")),
                new Feature("unknown.only", "x", singletonList("SOME_FUTURE_DESTINATION")))));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    final Map<String, Object> features = evaluations.get(0).getFeatures();
    assertEquals(4, features.size());
    assertFalse(
        features.containsKey("bandit.policy_id"),
        "a feature without HOOK is not delivered to hooks");
    assertFalse(
        features.containsKey("unknown.only"),
        "a feature with only unknown destinations goes nowhere");
    assertEquals("q4-global", features.get("holdout.key"));
    assertEquals("status_quo", features.get("holdout.assignment_group"));
    assertEquals(0.5, features.get("holdout.weight"));
    assertEquals(true, features.get("holdout.should_include_in_holdout_analysis"));
    assertEquals(
        "q4-global",
        details.getFlagMetadata().getString(DDEvaluator.METADATA_FEATURE_PREFIX + "holdout.key"));
    assertThrows(UnsupportedOperationException.class, () -> features.put("other", "value"));
  }

  @Test
  void warnsOnceForEachUnknownDestination() throws Exception {
    DDEvaluator.WARNED_FEATURE_DESTINATIONS.clear();
    start(
        true,
        configuration(
            Boolean.TRUE,
            Arrays.asList(
                new Feature("holdout.key", "q4-global", Arrays.asList("HOOK", "NEW_A")),
                new Feature("other", "x", Arrays.asList("NEW_A", "NEW_B", "EXPOSURE")))));

    evaluate("user-1");
    evaluate("user-2");

    assertEquals(
        new HashSet<>(Arrays.asList("NEW_A", "NEW_B")), DDEvaluator.WARNED_FEATURE_DESTINATIONS);
    assertEquals(1, evaluations.get(1).getFeatures().size());
    DDEvaluator.WARNED_FEATURE_DESTINATIONS.clear();
  }

  @Test
  void stopsRememberingUnknownDestinationsAtTheCap() throws Exception {
    DDEvaluator.WARNED_FEATURE_DESTINATIONS.clear();
    for (int i = 0; i < DDEvaluator.MAX_WARNED_FEATURE_DESTINATIONS; i++) {
      DDEvaluator.WARNED_FEATURE_DESTINATIONS.add("OLD_" + i);
    }
    try {
      start(
          true,
          configuration(
              Boolean.TRUE,
              singletonList(
                  new Feature("holdout.key", "q4-global", Arrays.asList("HOOK", "NEW_A")))));

      evaluate("user-1");

      assertEquals(
          DDEvaluator.MAX_WARNED_FEATURE_DESTINATIONS,
          DDEvaluator.WARNED_FEATURE_DESTINATIONS.size());
      assertFalse(DDEvaluator.WARNED_FEATURE_DESTINATIONS.contains("NEW_A"));
      assertEquals(
          Collections.singletonMap("holdout.key", "q4-global"), evaluations.get(0).getFeatures());
    } finally {
      DDEvaluator.WARNED_FEATURE_DESTINATIONS.clear();
    }
  }

  @Test
  void hasNoFeaturesWhenTheSplitHasNone() throws Exception {
    start(true, configuration(Boolean.TRUE, null));

    evaluate("user-1");

    assertTrue(evaluations.get(0).getFeatures().isEmpty());
  }

  @Test
  void aFailingCallbackDoesNotAffectTheEvaluation() throws Exception {
    start(true, configuration(Boolean.TRUE, null));
    OpenFeatureAPI.getInstance()
        .addHooks(
            new ExposureHook(
                evaluation -> {
                  throw new IllegalStateException("warehouse unavailable");
                }));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    assertNull(details.getErrorCode());
    assertEquals(Integer.valueOf(1), details.getValue());
    assertEquals(1, sentToDatadog.size());
  }

  @Test
  void anEvaluationIsAnExposureOnlyWithEverythingAnExposureNeeds() {
    assertFalse(evaluation("on", null).isExposure());
    assertFalse(evaluation(null, exposureMetadata("allocation", true, false)).isExposure());
    assertFalse(evaluation("on", exposureMetadata(null, true, false)).isExposure());
    assertFalse(evaluation("on", exposureMetadata("allocation", false, false)).isExposure());
    assertTrue(evaluation("on", exposureMetadata("allocation", true, false)).shouldSend());
    assertFalse(evaluation("on", exposureMetadata("allocation", true, false)).isCacheHit());
    assertTrue(evaluation("on", exposureMetadata("allocation", true, true)).isCacheHit());
    assertFalse(
        evaluation("on", exposureMetadata("allocation", false, true)).isCacheHit(),
        "an evaluation that is not an exposure is never a cache hit");
    assertFalse(evaluation("on", exposureMetadata("allocation", true, true)).shouldSend());
  }

  @Test
  void featuresIgnoreOtherMetadataAndAreEmptyWithoutMetadata() {
    assertTrue(evaluation("on", null).getFeatures().isEmpty());
    final ExposureHook.Evaluation evaluation =
        evaluation(
            "on",
            ImmutableMetadata.builder()
                .addString("allocationKey", "allocation")
                .addString(DDEvaluator.METADATA_FEATURE_PREFIX + "holdout.key", "q4-global")
                .build());

    assertEquals(1, evaluation.getFeatures().size());
    assertEquals("q4-global", evaluation.getFeatures().get("holdout.key"));
  }

  @Test
  void datadogLoggingStaysOnWhenItsSettingCannotBeRead() {
    assertTrue(DatadogExposureLoggingGate.isEnabled());
    assertFalse(DatadogExposureLoggingGate.isEnabled(() -> false));
    assertTrue(
        DatadogExposureLoggingGate.isEnabled(
            () -> {
              throw new NoClassDefFoundError("old agent");
            }));
  }

  @Test
  void theHookContainsCallbackFailures() {
    final HookContext<Object> context =
        HookContext.<Object>builder()
            .flagKey("flag")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(new MutableContext("user-1"))
            .build();
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).build();

    assertDoesNotThrow(
        () ->
            new ExposureHook(
                    evaluation -> {
                      throw new IllegalStateException("warehouse unavailable");
                    })
                .finallyAfter(context, details, emptyMap()));
    assertDoesNotThrow(
        () ->
            new ExposureHook(
                    evaluation -> {
                      throw new NoClassDefFoundError("missing class");
                    })
                .finallyAfter(context, details, emptyMap()));
  }

  @Test
  void requiresACallback() {
    assertThrows(NullPointerException.class, () -> new ExposureHook(null));
  }

  @Test
  void featuresAreSkippedWithAnAgentWhoseSplitHasNoFeatures() {
    assertTrue(DDEvaluator.splitFeaturesSupported(Split.class));
    assertFalse(DDEvaluator.splitFeaturesSupported(LegacySplit.class));
  }

  /** A Split from an agent that predates features. */
  static final class LegacySplit {
    public Integer serialId;
  }

  private void start(final boolean sendToDatadog, final ServerConfiguration configuration)
      throws Exception {
    FeatureFlaggingGateway.dispatch(configuration);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(
        new Provider(
            new Options().initTimeout(10, SECONDS), new DDEvaluator(mock(Runnable.class))) {
          @Override
          Hook buildExposureLoggingHook() {
            return new ExposureLoggingHook<>(ExposureDeduplicationCache.INSTANCE, sendToDatadog);
          }
        });
    api.addHooks(new ExposureHook(evaluations::add));
    client = api.getClient();
  }

  private static ExposureHook.Evaluation evaluation(
      final String variant, final ImmutableMetadata metadata) {
    final FlagEvaluationDetails.FlagEvaluationDetailsBuilder<Object> builder =
        FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).variant(variant);
    if (metadata != null) {
      builder.flagMetadata(metadata);
    }
    return new ExposureHook.Evaluation(new MutableContext("user-1"), builder.build());
  }

  private static ImmutableMetadata exposureMetadata(
      final String allocationKey, final boolean doLog, final boolean cacheHit) {
    final ImmutableMetadata.ImmutableMetadataBuilder builder =
        ImmutableMetadata.builder()
            .addBoolean(DDEvaluator.METADATA_DO_LOG, doLog)
            .addBoolean(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT, cacheHit);
    if (allocationKey != null) {
      builder.addString("allocationKey", allocationKey);
    }
    return builder.build();
  }

  private FlagEvaluationDetails<Integer> evaluate(final String targetingKey) {
    return client.getIntegerDetails("flag", 0, new MutableContext(targetingKey));
  }

  private List<Boolean> shouldSend() {
    final List<Boolean> result = new ArrayList<>();
    for (final ExposureHook.Evaluation evaluation : evaluations) {
      result.add(evaluation.shouldSend());
    }
    return result;
  }

  private static ServerConfiguration configuration(
      final Boolean doLog, final List<Feature> features) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), 7, features);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), doLog);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return new ServerConfiguration("", "", false, null, flags);
  }
}
