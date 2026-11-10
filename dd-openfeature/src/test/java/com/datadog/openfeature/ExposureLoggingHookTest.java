package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;
import static com.datadog.openfeature.internal.ufc.ConditionOperator.ONE_OF;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.Provider.Options;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.evaluation.FlagEvaluator.DependencyExposure;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationWriter;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.DefaultHookData;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.multiprovider.MultiProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExposureLoggingHookTest {

  private ExposureTestEvaluator evaluator;
  private List<ExposureEvent> dispatched;
  private Client client;

  @BeforeEach
  void setUp() throws Exception {
    this.evaluator = new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    this.dispatched = this.evaluator.sentToDatadog;
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), this.evaluator));
    this.client = api.getClient();
  }

  @AfterEach
  void tearDown() {
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
  }

  @Test
  void sendsExposuresWhenWrappedInAMultiProvider() throws Exception {
    final ExposureTestEvaluator wrapped =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    OpenFeatureAPI.getInstance()
        .setProviderAndWait(
            new MultiProvider(
                singletonList(new Provider(new Options().initTimeout(10, SECONDS), wrapped))));

    evaluate("user-1");
    evaluate("user-1");

    assertEquals(1, wrapped.sentToDatadog.size());
    assertEquals("user-1", wrapped.sentToDatadog.get(0).subject.id);
  }

  @Test
  void sendsOneExposurePerSubject() {
    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertEquals(2, this.dispatched.size());
    assertEquals("user-1", this.dispatched.get(0).subject.id);
    assertEquals("user-2", this.dispatched.get(1).subject.id);
    assertEquals("flag", this.dispatched.get(0).flag.key);
    assertEquals("allocation", this.dispatched.get(0).allocation.key);
    assertEquals("on", this.dispatched.get(0).variant.key);
    assertEquals(Integer.valueOf(7), this.dispatched.get(0).serial_id);
    assertNull(this.dispatched.get(0).features);
  }

  @Test
  void stampsWhetherTheSubjectWasAlreadyExposed() {
    assertEquals(Boolean.FALSE, cacheHit(evaluate("user-1")));
    assertEquals(Boolean.TRUE, cacheHit(evaluate("user-1")));
  }

  @Test
  void sendsAgainWhenTheVariantChanges() {
    evaluate("user-1");
    this.evaluator.accept(configuration("off", Boolean.TRUE));
    evaluate("user-1");

    assertEquals(2, this.dispatched.size());
    assertEquals("off", this.dispatched.get(1).variant.key);
  }

  @Test
  void aFailedEvaluationSendsNothingAndLeavesTheSubjectUnexposed() {
    final Hook<Integer> failingAfterHook =
        new Hook<Integer>() {
          @Override
          public void after(
              final HookContext<Integer> ctx,
              final FlagEvaluationDetails<Integer> details,
              final Map<String, Object> hints) {
            throw new IllegalStateException("application validation failed");
          }
        };
    OpenFeatureAPI.getInstance().addHooks(failingAfterHook);

    final FlagEvaluationDetails<Integer> failed = evaluate("user-1");

    assertEquals(ErrorCode.GENERAL, failed.getErrorCode());
    assertTrue(this.dispatched.isEmpty());
    assertEquals(0, this.evaluator.cache.size());

    OpenFeatureAPI.getInstance().clearHooks();
    evaluate("user-1");

    assertEquals(1, this.dispatched.size());
  }

  @Test
  void sendsNothingForAnAllocationThatDoesNotLogExposures() {
    this.evaluator.accept(configuration("on", Boolean.FALSE));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    assertTrue(this.dispatched.isEmpty());
    assertNull(cacheHit(details));
  }

  @Test
  void publishesEligibleDependencyExposuresThroughTheRootHookLifecycle() throws Exception {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(true), true);
    OpenFeatureAPI.getInstance()
        .setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), dependent));

    OpenFeatureAPI.getInstance()
        .getClient()
        .getIntegerDetails("root", 0, new MutableContext("user-1"));

    assertEquals(1, dependent.sentToDatadog.size());
    assertEquals("dependency", dependent.sentToDatadog.get(0).flag.key);
  }

  @Test
  void doesNotStageIneligibleDependencyExposures() throws Exception {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(false), true);
    OpenFeatureAPI.getInstance()
        .setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), dependent));

    OpenFeatureAPI.getInstance()
        .getClient()
        .getIntegerDetails("root", 0, new MutableContext("user-1"));

    assertTrue(dependent.sentToDatadog.isEmpty());
  }

  @Test
  void ordinaryFlagLifecycleDoesNotCreateADependencyScope() {
    final HookContext<Object> context = hookContext();

    final Object scope =
        this.evaluator.beginDependencyExposureScope(context.getFlagKey(), context.getCtx());
    assertNull(this.evaluator.dependencyTelemetryToken(scope));
    assertTrue(this.evaluator.endDependencyExposureScope().isEmpty());
  }

  @Test
  void finallyAfterCleansUpAStartedScopeWhenProviderEvaluationWasSkipped() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(true), true);
    final ExposureLoggingHook<Integer> hook = new ExposureLoggingHook<>(() -> dependent);
    final HookContext<Integer> context =
        HookContext.<Integer>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(new MutableContext("subject"))
            .build();

    hook.before(context, emptyMap());
    hook.finallyAfter(
        context,
        FlagEvaluationDetails.<Integer>builder()
            .flagKey("root")
            .value(0)
            .reason("ERROR")
            .errorCode(ErrorCode.GENERAL)
            .build(),
        emptyMap());

    assertTrue(dependent.endDependencyExposureScope().isEmpty());
  }

  @Test
  void skippedNestedBeforeCannotReadTheOuterBatchForTheSameFlagAndContext() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(true), true);
    final ExposureLoggingHook<Integer> hook = new ExposureLoggingHook<>(() -> dependent);
    final MutableContext context = new MutableContext("subject");
    final HookContext<Integer> outer =
        HookContext.<Integer>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(context)
            .hookData(new DefaultHookData())
            .build();
    final HookContext<Integer> skippedNested =
        HookContext.<Integer>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(context)
            .hookData(new DefaultHookData())
            .build();
    final Map<String, Object> outerHints = new HashMap<>();
    final Map<String, Object> skippedNestedHints = new HashMap<>();

    hook.before(outer, outerHints);
    final ProviderEvaluation<Integer> evaluation =
        dependent.evaluate(Integer.class, "root", 0, context);
    final FlagEvaluationDetails<Integer> outerDetails =
        FlagEvaluationDetails.from(evaluation, "root");
    final FlagEvaluationDetails<Integer> skippedNestedDetails =
        FlagEvaluationDetails.<Integer>builder()
            .flagKey("root")
            .value(0)
            .reason("ERROR")
            .errorCode(ErrorCode.GENERAL)
            .build();

    final Object outerScope = outer.getHookData().get(DDEvaluator.DEPENDENCY_TELEMETRY_SCOPE);
    assertNull(
        dependent.dependencyTelemetryToken(
            skippedNested.getHookData().get(DDEvaluator.DEPENDENCY_TELEMETRY_SCOPE)));
    assertNotNull(dependent.dependencyTelemetryToken(outerScope));

    final List<FlagEvalEvent> events = new java.util.ArrayList<>();
    final FlagEvaluationWriter writer =
        new FlagEvaluationWriter() {
          @Override
          public boolean hasCapacityForEnqueue() {
            return true;
          }

          @Override
          public void countPreQueueOverflow() {}

          @Override
          public void countContextTruncated(final String reason) {}

          @Override
          public void enqueue(final FlagEvalEvent event) {
            events.add(event);
          }
        };
    final FlagEvalLoggingHook<Integer> evaluationHook =
        new FlagEvalLoggingHook<>(() -> writer, () -> dependent);
    evaluationHook.before(outer, outerHints);
    evaluationHook.finallyAfter(skippedNested, skippedNestedDetails, skippedNestedHints);
    evaluationHook.finallyAfter(outer, outerDetails, outerHints);

    assertEquals(3, events.size(), "inner root, then outer dependency and root");
    assertEquals("root", events.get(0).flagKey);
    assertEquals("dependency", events.get(1).flagKey);
    assertEquals("root", events.get(2).flagKey);

    hook.finallyAfter(outer, outerDetails, outerHints);
    assertEquals(1, dependent.sentToDatadog.size());
  }

  @Test
  void dependencyExposuresReuseOneDetachedContextSnapshot() throws Exception {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(twoLoggedDependenciesConfiguration(), true);
    OpenFeatureAPI.getInstance()
        .setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), dependent));
    final MutableContext context = new MutableContext("user-1");
    context.add("region", "us-east-1");

    OpenFeatureAPI.getInstance().getClient().getIntegerDetails("root", 0, context);
    context.add("region", "eu-west-1");

    assertEquals(2, dependent.sentToDatadog.size());
    assertSame(
        dependent.sentToDatadog.get(0).subject.attributes,
        dependent.sentToDatadog.get(1).subject.attributes);
    assertEquals("us-east-1", dependent.sentToDatadog.get(0).subject.attributes.get("region"));
  }

  @Test
  void configurationRefreshCannotChangeTheGraphInsideAClientHookLifecycle() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(ordinaryRootConfiguration(), true);
    final ExposureLoggingHook<Integer> hook = new ExposureLoggingHook<>(() -> dependent);
    final MutableContext evaluationContext = new MutableContext("subject");
    final HookContext<Integer> hookContext =
        HookContext.<Integer>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(evaluationContext)
            .hookData(new DefaultHookData())
            .build();

    hook.before(hookContext, emptyMap());
    dependent.accept(dependentConfiguration(true));
    final ProviderEvaluation<Integer> pinned =
        dependent.evaluate(Integer.class, "root", 0, evaluationContext);
    final ProviderEvaluation<String> unrelated =
        dependent.evaluate(String.class, "dependency", "default", evaluationContext);
    hook.finallyAfter(hookContext, FlagEvaluationDetails.from(pinned, "root"), emptyMap());

    assertEquals(2, pinned.getValue(), "evaluation uses the snapshot pinned by before");
    assertEquals(
        "on-value",
        unrelated.getValue(),
        "an unrelated evaluation after the root uses the refreshed configuration");
    assertTrue(dependent.sentToDatadog.isEmpty());

    final HookContext<Integer> refreshedContext =
        HookContext.<Integer>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(evaluationContext)
            .hookData(new DefaultHookData())
            .build();
    hook.before(refreshedContext, emptyMap());
    final ProviderEvaluation<Integer> refreshed =
        dependent.evaluate(Integer.class, "root", 0, evaluationContext);
    hook.finallyAfter(refreshedContext, FlagEvaluationDetails.from(refreshed, "root"), emptyMap());

    assertEquals(1, refreshed.getValue());
    assertEquals(1, dependent.sentToDatadog.size());
    assertEquals("dependency", dependent.sentToDatadog.get(0).flag.key);
  }

  @Test
  void directProviderEvaluationDoesNotRetainOrExposeDependencyState() throws Exception {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(true), true);
    final Provider provider = new Provider(new Options().initTimeout(10, SECONDS), dependent);
    final MutableContext context = new MutableContext("user-1");
    provider.initialize(context);

    final ProviderEvaluation<Integer> evaluation =
        provider.getIntegerEvaluation("root", 0, context);

    assertTrue(
        evaluation.getFlagMetadata() == null
            || !evaluation
                .getFlagMetadata()
                .asUnmodifiableMap()
                .containsKey("__dd_dependency_exposures_token"));
    new ExposureLoggingHook<Integer>(() -> dependent)
        .finallyAfter(
            HookContext.<Integer>builder()
                .flagKey("root")
                .type(FlagValueType.INTEGER)
                .defaultValue(0)
                .ctx(context)
                .build(),
            FlagEvaluationDetails.from(evaluation, "root"),
            emptyMap());
    assertTrue(dependent.sentToDatadog.isEmpty());

    provider.shutdown();
  }

  @Test
  void reentrantDirectEvaluationCannotReplaceTheOuterRootsDependencyExposures() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(reentrantDependentConfiguration(), true);
    final ExposureLoggingHook<Integer> hook = new ExposureLoggingHook<>(() -> dependent);
    final MutableContext outerContext = new MutableContext("subject-a");
    final MutableContext reentrantContext = new MutableContext("subject-b");
    final HookContext<Integer> outerHookContext =
        HookContext.<Integer>builder()
            .flagKey("root-a")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(outerContext)
            .hookData(new DefaultHookData())
            .build();
    final Map<String, Object> hints = new HashMap<>();

    hook.before(outerHookContext, hints);
    final ProviderEvaluation<Integer> outerEvaluation =
        dependent.evaluate(Integer.class, "root-a", 0, outerContext);
    dependent.evaluate(Integer.class, "root-b", 0, reentrantContext);
    hook.finallyAfter(
        outerHookContext, FlagEvaluationDetails.from(outerEvaluation, "root-a"), hints);

    assertEquals(1, dependent.sentToDatadog.size());
    assertEquals("dependency-a", dependent.sentToDatadog.get(0).flag.key);
    assertEquals("subject-a", dependent.sentToDatadog.get(0).subject.id);
  }

  @Test
  void unmatchedNestedFinallyCannotPopTheOuterDependencyExposureScope() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(reentrantDependentConfiguration(), true);
    final ExposureLoggingHook<Integer> hook = new ExposureLoggingHook<>(() -> dependent);
    final MutableContext outerContext = new MutableContext("subject-a");
    final HookContext<Integer> outerHookContext =
        HookContext.<Integer>builder()
            .flagKey("root-a")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(outerContext)
            .hookData(new DefaultHookData())
            .build();
    final HookContext<Integer> innerHookContext =
        HookContext.<Integer>builder()
            .flagKey("root-b")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(new MutableContext("subject-b"))
            .hookData(new DefaultHookData())
            .build();
    final Map<String, Object> outerHints = new HashMap<>();

    hook.before(outerHookContext, outerHints);
    final ProviderEvaluation<Integer> outerEvaluation =
        dependent.evaluate(Integer.class, "root-a", 0, outerContext);

    // OpenFeature invokes finally hooks for the inner evaluation even when an earlier before hook
    // throws before this hook's before method is reached.
    hook.finallyAfter(
        innerHookContext,
        FlagEvaluationDetails.<Integer>builder()
            .flagKey("root-b")
            .value(0)
            .errorCode(ErrorCode.GENERAL)
            .build(),
        emptyMap());
    hook.finallyAfter(
        outerHookContext, FlagEvaluationDetails.from(outerEvaluation, "root-a"), outerHints);

    assertEquals(1, dependent.sentToDatadog.size());
    assertEquals("dependency-a", dependent.sentToDatadog.get(0).flag.key);
    assertEquals("subject-a", dependent.sentToDatadog.get(0).subject.id);
    assertTrue(dependent.endDependencyExposureScope().isEmpty());

    // A subsequent lifecycle proves the evaluator's token stack was removed as well.
    final Map<String, Object> innerHints = new HashMap<>();
    hook.before(innerHookContext, innerHints);
    final ProviderEvaluation<Integer> innerEvaluation =
        dependent.evaluate(Integer.class, "root-b", 0, innerHookContext.getCtx());
    hook.finallyAfter(
        innerHookContext, FlagEvaluationDetails.from(innerEvaluation, "root-b"), innerHints);
    assertEquals(2, dependent.sentToDatadog.size());
    assertEquals("dependency-b", dependent.sentToDatadog.get(1).flag.key);
  }

  @Test
  void emptyMatchingEvaluationSealsTheOuterDependencyExposureScope() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(false), true);
    final ExposureLoggingHook<Integer> hook = new ExposureLoggingHook<>(() -> dependent);
    final MutableContext context = new MutableContext("subject-a");
    final HookContext<Integer> hookContext =
        HookContext.<Integer>builder()
            .flagKey("root")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(context)
            .build();
    final Map<String, Object> hints = new HashMap<>();

    hook.before(hookContext, hints);
    final ProviderEvaluation<Integer> outerEvaluation =
        dependent.evaluate(Integer.class, "root", 0, context);
    dependent.accept(dependentConfiguration(true));
    dependent.evaluate(Integer.class, "root", 0, context);
    hook.finallyAfter(hookContext, FlagEvaluationDetails.from(outerEvaluation, "root"), hints);

    assertTrue(dependent.sentToDatadog.isEmpty());
  }

  @Test
  void shutdownDiscardsTheCurrentDependencyExposureScope() {
    final ExposureTestEvaluator dependent =
        new ExposureTestEvaluator(dependentConfiguration(true), true);
    final MutableContext context = new MutableContext("user-1");
    dependent.beginDependencyExposureScope("root", context);
    dependent.evaluate(Integer.class, "root", 0, context);

    dependent.shutdown();

    assertTrue(dependent.endDependencyExposureScope().isEmpty());
  }

  @Test
  void recordsButDoesNotSendWhenDatadogLoggingIsOff() {
    final ExposureTestEvaluator notSending =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), false);
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> notSending);

    hook.finallyAfter(hookContext(), details("on", exposureMetadata(true, false, "a")), emptyMap());

    assertTrue(notSending.sentToDatadog.isEmpty());
    assertEquals(1, notSending.cache.size());
  }

  @Test
  void sendsNothingWithoutTheDetailsAnExposureNeeds() {
    final ExposureTestEvaluator target =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> target);
    final HookContext<Object> withContext = hookContext();

    hook.finallyAfter(withContext, null, emptyMap());
    hook.finallyAfter(withContext, details("variant", null), emptyMap());
    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(false, false, "a")), emptyMap());
    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(true, true, "a")), emptyMap());
    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(true, false, null)), emptyMap());
    hook.finallyAfter(withContext, details(null, exposureMetadata(true, false, "a")), emptyMap());
    hook.finallyAfter(null, details("variant", exposureMetadata(true, false, "a")), emptyMap());
    hook.finallyAfter(
        withContext,
        FlagEvaluationDetails.<Object>builder()
            .flagKey("flag")
            .value(1)
            .variant("variant")
            .errorCode(ErrorCode.GENERAL)
            .flagMetadata(exposureMetadata(true, false, "a"))
            .build(),
        emptyMap());

    assertTrue(target.sentToDatadog.isEmpty());
    assertEquals(0, target.cache.size());

    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(true, false, "a")), emptyMap());

    assertEquals(1, target.sentToDatadog.size());
    assertEquals(1, target.cache.size());
  }

  @Test
  void sendsNothingWithoutAnInitializedDatadogEvaluator() {
    final ExposureLoggingHook<Object> withoutEvaluator = new ExposureLoggingHook<>(() -> null);
    final DDEvaluator uninitialized =
        new DDEvaluator(() -> {}, AgentBridge.NONE, TestSettings.of());
    final ExposureLoggingHook<Object> withoutRuntime =
        new ExposureLoggingHook<>(() -> uninitialized);

    assertDoesNotThrow(() ->
        withoutEvaluator.finallyAfter(
            hookContext(), details("on", exposureMetadata(true, false, "a")), emptyMap()));
    assertDoesNotThrow(() ->
        withoutRuntime.finallyAfter(
            hookContext(), details("on", exposureMetadata(true, false, "a")), emptyMap()));
  }

  @Test
  void readsTheSerialIdFromTheMetadata() {
    final ExposureTestEvaluator target =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> target);
    final ImmutableMetadata metadata =
        ImmutableMetadata.builder()
            .addBoolean(DO_LOG, true)
            .addBoolean(EXPOSURE_CACHE_HIT, false)
            .addString(ALLOCATION_KEY, "a")
            .addInteger(SPLIT_SERIAL_ID, 42)
            .build();

    hook.finallyAfter(hookContext(), details("on", metadata), emptyMap());

    assertEquals(Integer.valueOf(42), target.sentToDatadog.get(0).serial_id);
    assertTrue(target.cache.contains("flag", "user-1", "a", "on", 42));
  }

  @Test
  void containsFailuresOfTheEvaluator() {
    final ExposureTestEvaluator failing =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true) {
          @Override
          void recordExposure(final ExposureEvent event) {
            throw new IllegalStateException("queue failure");
          }
        };
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> failing);
    final HookContext<Object> context = hookContext();

    assertDoesNotThrow(() ->
        hook.finallyAfter(context, details("on", exposureMetadata(true, false, "a")), emptyMap()));
    assertDoesNotThrow(() ->
        hook.finallyAfter(context, details("off", exposureMetadata(true, false, "a")), emptyMap()));
  }

  @Test
  void dependencyExposureFailureDoesNotSuppressTheRootExposure() {
    final ExposureTestEvaluator failingDependencies =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true) {
          @Override
          List<FlagEvaluationDetails<Object>> recordDependencyExposures(
              final List<DependencyExposure> exposures, final EvaluationContext context) {
            throw new IllegalStateException("dependency queue failure");
          }
        };
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> failingDependencies);
    final HookContext<Object> context = hookContext();

    hook.before(context, emptyMap());
    assertDoesNotThrow(() ->
        hook.finallyAfter(context, details("on", exposureMetadata(true, false, "a")), emptyMap()));

    assertEquals(1, failingDependencies.sentToDatadog.size());
    assertEquals("flag", failingDependencies.sentToDatadog.get(0).flag.key);
  }

  private static Object cacheHit(final FlagEvaluationDetails<Integer> details) {
    return details.getFlagMetadata().asUnmodifiableMap().get(EXPOSURE_CACHE_HIT);
  }

  private static HookContext<Object> hookContext() {
    return HookContext.<Object>builder()
        .flagKey("flag")
        .type(FlagValueType.INTEGER)
        .defaultValue(0)
        .ctx(new MutableContext("user-1"))
        .build();
  }

  private static FlagEvaluationDetails<Object> details(
      final String variant, final ImmutableMetadata metadata) {
    final FlagEvaluationDetails.FlagEvaluationDetailsBuilder<Object> builder =
        FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).variant(variant);
    if (metadata != null) {
      builder.flagMetadata(metadata);
    }
    return builder.build();
  }

  private static ImmutableMetadata exposureMetadata(
      final boolean doLog, final boolean cacheHit, final String allocationKey) {
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

  private static ServerConfiguration configuration(final String variantKey, final Boolean doLog) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    variations.put("off", new Variant("off", 0));
    final Split split = new Split(emptyList(), variantKey, emptyMap(), 7);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), doLog);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static ServerConfiguration dependentConfiguration(final boolean dependencyDoLog) {
    final Map<String, Flag> flags = new HashMap<>();
    addDependentFlagPair(flags, "root", "dependency", dependencyDoLog);
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static ServerConfiguration ordinaryRootConfiguration() {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("selected", new Variant("selected", 2));
    final Allocation allocation =
        new Allocation(
            "ordinary-root",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "selected", emptyMap(), 1)),
            false);
    return new ServerConfiguration(
        "",
        "",
        false,
        null,
        singletonMap(
            "root",
            new Flag("root", true, ValueType.INTEGER, variations, singletonList(allocation))));
  }

  private static ServerConfiguration reentrantDependentConfiguration() {
    final Map<String, Flag> flags = new HashMap<>();
    addDependentFlagPair(flags, "root-a", "dependency-a", true);
    addDependentFlagPair(flags, "root-b", "dependency-b", true);
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static ServerConfiguration twoLoggedDependenciesConfiguration() {
    final Map<String, Flag> flags = new HashMap<>();
    final List<ConditionConfiguration> conditions = new java.util.ArrayList<>();
    for (final String dependencyKey : java.util.Arrays.asList("first", "second")) {
      final Map<String, Variant> variations = new HashMap<>();
      variations.put("on", new Variant("on", "on-value"));
      flags.put(
          dependencyKey,
          new Flag(
              dependencyKey,
              true,
              ValueType.STRING,
              variations,
              singletonList(
                  new Allocation(
                      dependencyKey + "-allocation",
                      null,
                      null,
                      null,
                      singletonList(new Split(emptyList(), "on", emptyMap(), 8)),
                      true))));
      conditions.add(new ConditionConfiguration(ONE_OF, null, dependencyKey, singletonList("on")));
    }
    final Map<String, Variant> rootVariations = new HashMap<>();
    rootVariations.put("selected", new Variant("selected", 1));
    flags.put(
        "root",
        new Flag(
            "root",
            true,
            ValueType.INTEGER,
            rootVariations,
            singletonList(
                new Allocation(
                    "root-allocation",
                    singletonList(new Rule(conditions)),
                    null,
                    null,
                    singletonList(new Split(emptyList(), "selected", emptyMap(), 9)),
                    false))));
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static void addDependentFlagPair(
      final Map<String, Flag> flags,
      final String rootKey,
      final String dependencyKey,
      final boolean dependencyDoLog) {
    final Map<String, Variant> dependencyVariations = new HashMap<>();
    dependencyVariations.put("on", new Variant("on", "on-value"));
    final Allocation dependencyAllocation =
        new Allocation(
            dependencyKey + "-allocation",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 8)),
            dependencyDoLog);
    final Flag dependency =
        new Flag(
            dependencyKey,
            true,
            ValueType.STRING,
            dependencyVariations,
            singletonList(dependencyAllocation));

    final Map<String, Variant> rootVariations = new HashMap<>();
    rootVariations.put("selected", new Variant("selected", 1));
    final Rule dependencyRule =
        new Rule(
            singletonList(
                new ConditionConfiguration(ONE_OF, null, dependencyKey, singletonList("on"))));
    final Allocation rootAllocation =
        new Allocation(
            rootKey + "-allocation",
            singletonList(dependencyRule),
            null,
            null,
            singletonList(new Split(emptyList(), "selected", emptyMap(), 9)),
            false);
    flags.put(dependencyKey, dependency);
    flags.put(
        rootKey,
        new Flag(rootKey, true, ValueType.INTEGER, rootVariations, singletonList(rootAllocation)));
  }
}
