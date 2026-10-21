package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.Provider.Options;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationWriter;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Feature;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExposureHookTest {

  private final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
  private ExposureTestEvaluator evaluator;
  private Client client;

  @AfterEach
  void tearDown() {
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
  }

  @Test
  void shouldSendMatchesTheExposuresSentToDatadog() throws Exception {
    start(true, configuration(Boolean.TRUE, null));

    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertEquals(asList(true, false, true), shouldSend());
    assertEquals(2, this.evaluator.sentToDatadog.size());
    assertEquals("user-1", this.evaluator.sentToDatadog.get(0).subject.id);
    assertEquals("user-2", this.evaluator.sentToDatadog.get(1).subject.id);
  }

  @Test
  void customerHookStillReceivesExposuresWhenDatadogLoggingIsOff() throws Exception {
    start(false, configuration(Boolean.TRUE, null));

    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertTrue(this.evaluator.sentToDatadog.isEmpty());
    assertEquals(asList(true, false, true), shouldSend());
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
    final ExposureHook.Evaluation evaluation = this.evaluations.get(0);
    assertFalse(evaluation.isExposure());
    assertFalse(evaluation.isCacheHit());
    assertFalse(evaluation.shouldSend());
  }

  @Test
  void anAllocationThatDoesNotLogExposuresIsNotAnExposure() throws Exception {
    start(true, configuration(Boolean.FALSE, null));

    evaluate("user-1");

    final ExposureHook.Evaluation evaluation = this.evaluations.get(0);
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
            asList(
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("holdout.assignment_group", "status_quo", singletonList("HOOK")),
                new Feature("holdout.weight", 0.5, asList("EVALUATION", "HOOK")),
                new Feature(
                    "holdout.should_include_in_holdout_analysis",
                    true,
                    asList("HOOK", "SOME_FUTURE_DESTINATION")),
                new Feature("bandit.policy_id", "p2", asList("EXPOSURE", "EVALUATION")),
                new Feature("unknown.only", "x", singletonList("SOME_FUTURE_DESTINATION")))));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    final Map<String, Object> features = this.evaluations.get(0).getFeatures();
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
        details.getFlagMetadata().asUnmodifiableMap().get(FEATURE_PREFIX + "holdout.key"));
    assertThrows(UnsupportedOperationException.class, () -> features.put("other", "value"));
  }

  @Test
  void routesEachFeatureToItsDestinations() throws Exception {
    start(
        true,
        configuration(
            Boolean.TRUE,
            asList(
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("bandit.arm", "a", singletonList("EXPOSURE")),
                new Feature("experiment.tag", "pricing", singletonList("EVALUATION")),
                new Feature("bandit.policy_id", "p2", asList("EXPOSURE", "EVALUATION")))));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    final ImmutableMetadata metadata = details.getFlagMetadata();
    assertEquals(
        singletonMap("holdout.key", "q4-global"),
        SplitFeatureMetadata.featuresWithPrefix(metadata, FEATURE_PREFIX));
    final Map<String, Object> exposureFeatures = new HashMap<>();
    exposureFeatures.put("bandit.arm", "a");
    exposureFeatures.put("bandit.policy_id", "p2");
    assertEquals(
        exposureFeatures,
        SplitFeatureMetadata.featuresWithPrefix(metadata, EXPOSURE_FEATURE_PREFIX));
    final Map<String, Object> evaluationFeatures = new HashMap<>();
    evaluationFeatures.put("experiment.tag", "pricing");
    evaluationFeatures.put("bandit.policy_id", "p2");
    assertEquals(
        evaluationFeatures,
        SplitFeatureMetadata.featuresWithPrefix(metadata, EVALUATION_FEATURE_PREFIX));
    assertEquals(singletonMap("holdout.key", "q4-global"), this.evaluations.get(0).getFeatures());
    assertEquals(exposureFeatures, this.evaluator.sentToDatadog.get(0).features);
  }

  @Test
  void aRepeatedKeyKeepsTheFirstFeatureForEveryDestination() throws Exception {
    start(
        true,
        configuration(
            Boolean.TRUE,
            asList(
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("holdout.key", "q1-global", asList("HOOK", "EXPOSURE")))));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    assertEquals(singletonMap("holdout.key", "q4-global"), this.evaluations.get(0).getFeatures());
    assertTrue(
        SplitFeatureMetadata.featuresWithPrefix(details.getFlagMetadata(), EXPOSURE_FEATURE_PREFIX)
            .isEmpty(),
        "the repeated feature's EXPOSURE destination is ignored with it");
    assertNull(this.evaluator.sentToDatadog.get(0).features);
  }

  @Test
  void sendsEvaluationFeaturesForAnAllocationThatDoesNotLogExposures() throws Exception {
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    start(
        true,
        configuration(
            Boolean.FALSE,
            singletonList(new Feature("bandit.policy_id", "p2", singletonList("EVALUATION")))));
    final FlagEvaluationWriter writer = capturingWriter(captured);
    OpenFeatureAPI.getInstance().addHooks(new FlagEvalLoggingHook<>(() -> writer));

    evaluate("user-1");

    assertEquals(singletonMap("bandit.policy_id", "p2"), captured.get().features);
    assertTrue(this.evaluator.sentToDatadog.isEmpty(), "the allocation does not log exposures");
  }

  @Test
  void hasNoFeaturesWhenTheSplitHasNone() throws Exception {
    start(true, configuration(Boolean.TRUE, null));

    evaluate("user-1");

    assertTrue(this.evaluations.get(0).getFeatures().isEmpty());
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
    assertEquals(1, this.evaluator.sentToDatadog.size());
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
  void anEvaluationWithAnErrorIsNotAnExposure() {
    final ExposureHook.Evaluation evaluation =
        new ExposureHook.Evaluation(
            new MutableContext("user-1"),
            FlagEvaluationDetails.<Object>builder()
                .flagKey("flag")
                .value(0)
                .variant("on")
                .errorCode(ErrorCode.GENERAL)
                .flagMetadata(exposureMetadata("allocation", true, false))
                .build());

    assertFalse(evaluation.isExposure());
    assertFalse(evaluation.shouldSend());
  }

  @Test
  void featuresIgnoreOtherMetadataAndAreEmptyWithoutMetadata() {
    assertTrue(evaluation("on", null).getFeatures().isEmpty());
    final ExposureHook.Evaluation evaluation =
        evaluation(
            "on",
            ImmutableMetadata.builder()
                .addString(ALLOCATION_KEY, "allocation")
                .addString(FEATURE_PREFIX + "holdout.key", "q4-global")
                .build());

    assertEquals(1, evaluation.getFeatures().size());
    assertEquals("q4-global", evaluation.getFeatures().get("holdout.key"));
  }

  @Test
  void theHookContainsCallbackFailures() {
    final HookContext<Object> context = hookContext();
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
  void keepsContainingCallbackFailuresAfterTheFirstOne() {
    final ExposureHook hook =
        new ExposureHook(
            evaluation -> {
              throw new IllegalStateException("warehouse unavailable");
            });
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).build();

    hook.finallyAfter(hookContext(), details, emptyMap());

    assertDoesNotThrow(() -> hook.finallyAfter(hookContext(), details, emptyMap()));
  }

  @Test
  void requiresACallback() {
    final NullPointerException error =
        assertThrows(NullPointerException.class, () -> new ExposureHook(null));
    assertEquals("callback", error.getMessage());
  }

  @Test
  void skipsTheCallbackWhenThereAreNoDetails() {
    final List<ExposureHook.Evaluation> seen = new ArrayList<>();

    new ExposureHook(seen::add).finallyAfter(hookContext(), null, emptyMap());

    assertTrue(seen.isEmpty());
  }

  private void start(final boolean sendToDatadog, final ServerConfiguration configuration)
      throws Exception {
    this.evaluator = new ExposureTestEvaluator(configuration, sendToDatadog);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), this.evaluator));
    api.addHooks(new ExposureHook(this.evaluations::add));
    this.client = api.getClient();
  }

  private static HookContext<Object> hookContext() {
    return HookContext.<Object>builder()
        .flagKey("flag")
        .type(FlagValueType.INTEGER)
        .defaultValue(0)
        .ctx(new MutableContext("user-1"))
        .build();
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
            .addBoolean(DO_LOG, doLog)
            .addBoolean(EXPOSURE_CACHE_HIT, cacheHit);
    if (allocationKey != null) {
      builder.addString(ALLOCATION_KEY, allocationKey);
    }
    return builder.build();
  }

  private FlagEvaluationDetails<Integer> evaluate(final String targetingKey) {
    return this.client.getIntegerDetails("flag", 0, new MutableContext(targetingKey));
  }

  private List<Boolean> shouldSend() {
    final List<Boolean> result = new ArrayList<>();
    for (final ExposureHook.Evaluation evaluation : this.evaluations) {
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

  private static FlagEvaluationWriter capturingWriter(final AtomicReference<FlagEvalEvent> ref) {
    final FlagEvaluationWriter writer = mock(FlagEvaluationWriter.class);
    when(writer.hasCapacityForEnqueue()).thenReturn(true);
    doAnswer(
            invocation -> {
              ref.set(invocation.getArgument(0));
              return null;
            })
        .when(writer)
        .enqueue(any());
    return writer;
  }
}
