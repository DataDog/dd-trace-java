package datadog.trace.api.openfeature;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.environment.EnvironmentVariables;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.SpanEnrichmentEvent;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.multiprovider.MultiProvider;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises real OpenFeature resolution and telemetry hooks, with capture at each output boundary.
 */
class EvaluationTelemetryOptOutTest {
  private static final String PARAMETER = "dd_evaluation_telemetry_opt_out";
  private static final String ENVIRONMENT_VARIABLE =
      "DD_FEATURE_FLAGS_EVALUATION_TELEMETRY_PER_SUBJECT_OPT_OUT_ENABLED";
  private static final String SYSTEM_PROPERTY =
      "dd.feature.flags.evaluation.telemetry.per.subject.opt.out.enabled";
  private EnvironmentVariables.EnvironmentVariablesProvider previousEnvironment;
  private String previousProperty;
  private String configuredEnvironmentValue;

  private final List<FlagEvalEvent> events = new CopyOnWriteArrayList<>();
  private final List<SpanEnrichmentEvent> spans = new CopyOnWriteArrayList<>();
  private final List<ExposureEvent> exposures = new CopyOnWriteArrayList<>();
  private final List<ExposureHook.Evaluation> customerEvaluations = new CopyOnWriteArrayList<>();
  private final AtomicInteger metrics = new AtomicInteger();
  private final FeatureFlaggingGateway.SpanEnrichmentListener spanListener = spans::add;
  private final FeatureFlaggingGateway.ExposureListener exposureListener = exposures::add;
  private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  private FlagEvaluationWriter previousWriter;
  private boolean previousEnqueueEnabled;
  private Client client;

  @BeforeEach
  void setUp() {
    previousEnvironment = EnvironmentVariables.provider;
    previousProperty = System.getProperty(SYSTEM_PROPERTY);
    System.clearProperty(SYSTEM_PROPERTY);
    EnvironmentVariables.provider =
        new EnvironmentVariables.EnvironmentVariablesProvider() {
          @Override
          public String get(final String name) {
            return ENVIRONMENT_VARIABLE.equals(name)
                ? configuredEnvironmentValue
                : previousEnvironment.get(name);
          }
        };
    api.setEvaluationContext(new MutableContext());
    GlobalOpenTelemetry.resetForTest();
    final OpenTelemetry otel = mock(OpenTelemetry.class, RETURNS_DEEP_STUBS);
    final LongCounter counter = mock(LongCounter.class);
    when(otel.getMeterProvider()
            .meterBuilder("ddtrace.openfeature")
            .build()
            .counterBuilder("feature_flag.evaluations")
            .setUnit("{evaluation}")
            .setDescription("Number of feature flag evaluations")
            .build())
        .thenReturn(counter);
    doAnswer(invocation -> metrics.addAndGet(1)).when(counter).add(eq(1L), any(Attributes.class));
    GlobalOpenTelemetry.set(otel);

    previousWriter = FeatureFlaggingGateway.getFlagEvalWriter();
    previousEnqueueEnabled = FeatureFlaggingGateway.isFlagEvaluationEnqueueEnabled();
    final FlagEvaluationWriter writer = mock(FlagEvaluationWriter.class);
    when(writer.hasCapacityForEnqueue()).thenReturn(true);
    doAnswer(invocation -> events.add(invocation.getArgument(0))).when(writer).enqueue(any());
    FeatureFlaggingGateway.setFlagEvalWriter(writer);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    FeatureFlaggingGateway.addSpanEnrichmentListener(spanListener);
    FeatureFlaggingGateway.addExposureListener(exposureListener);
    ExposureDeduplicationCache.INSTANCE.clear();
    api.addHooks(new ExposureHook(customerEvaluations::add));
  }

  @AfterEach
  void tearDown() {
    EnvironmentVariables.provider = previousEnvironment;
    if (previousProperty == null) {
      System.clearProperty(SYSTEM_PROPERTY);
    } else {
      System.setProperty(SYSTEM_PROPERTY, previousProperty);
    }
    api.clearHooks();
    api.setEvaluationContext(new MutableContext());
    api.shutdown();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    FeatureFlaggingGateway.removeSpanEnrichmentListener(spanListener);
    FeatureFlaggingGateway.removeExposureListener(exposureListener);
    FeatureFlaggingGateway.setFlagEvalWriter(previousWriter);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(previousEnqueueEnabled);
    ExposureDeduplicationCache.INSTANCE.clear();
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void environmentEnablesPerSubjectOptOut() throws Exception {
    configuredEnvironmentValue = "true";
    start(new Provider.Options());
    final FlagEvaluationDetails<Integer> details = evaluate("subject-1", new Value(true));
    assertEquals(1, details.getValue());
    assertEquals("on", details.getVariant());
    assertTelemetryCounts(0);
    assertEquals(1, exposures.size());
    evaluate("subject-2", new Value(false));
    evaluate("subject-3", null);
    assertTelemetryCounts(2);
    assertEquals(3, exposures.size());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"false", "invalid", ""})
  void environmentDefaultsToDisabled(final String value) throws Exception {
    configuredEnvironmentValue = value;
    start(new Provider.Options());
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(1);
    assertEquals(1, exposures.size());
  }

  @Test
  void explicitFalseOverridesEnabledEnvironment() throws Exception {
    configuredEnvironmentValue = "true";
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(false));
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(1);
    assertEquals(1, exposures.size());
  }

  @Test
  void explicitTrueOverridesDisabledEnvironment() throws Exception {
    configuredEnvironmentValue = "false";
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(true));
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(0);
    assertEquals(1, exposures.size());
  }

  @Test
  void systemPropertyOverridesEnvironment() throws Exception {
    configuredEnvironmentValue = "true";
    System.setProperty(SYSTEM_PROPERTY, "false");
    start(new Provider.Options());
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(1);
  }

  @Test
  void environmentIsCapturedAtProviderConstruction() throws Exception {
    configuredEnvironmentValue = "true";
    final Provider.Options options = new Provider.Options();
    start(options);
    configuredEnvironmentValue = "false";
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(0);
    api.shutdown();
    start(options);
    evaluate("subject-2", new Value(true));
    assertTelemetryCounts(1);
  }

  @Test
  void optedOutSubjectKeepsAssignmentAndExposureButEmitsNoEvaluationTelemetry() throws Exception {
    start();

    final FlagEvaluationDetails<Integer> details = evaluate("subject-1", new Value(true));

    assertNull(details.getErrorCode());
    assertEquals(1, details.getValue());
    assertEquals("on", details.getVariant());
    assertEquals(1, exposures.size());
    assertEquals(1, customerEvaluations.size());
    assertTelemetryCounts(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void telemetryAttributeRemainsAvailableForTargetingAndExposure(final boolean enabled)
      throws Exception {
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(enabled));

    for (final boolean observeFullEvaluationData : new boolean[] {false, true}) {
      FeatureFlaggingGateway.dispatch(targetingConfiguration(observeFullEvaluationData));
      final String subject = "subject-" + observeFullEvaluationData;
      final int previousEvents = events.size();
      final int previousExposures = exposures.size();

      final FlagEvaluationDetails<Integer> allowed = evaluate(subject, new Value(false));
      final FlagEvaluationDetails<Integer> optedOut = evaluate(subject, new Value(true));
      final FlagEvaluationDetails<Integer> repeated = evaluate(subject, new Value(true));
      final FlagEvaluationDetails<Integer> stringValue =
          evaluate(subject + "-string", new Value("true"));

      assertNull(allowed.getErrorCode());
      assertEquals(1, allowed.getValue());
      assertEquals("on", allowed.getVariant());
      for (final FlagEvaluationDetails<Integer> details : asList(optedOut, repeated, stringValue)) {
        assertNull(details.getErrorCode());
        assertEquals(2, details.getValue(), "the ordinary targeting rule sees the attribute");
        assertEquals("targeted", details.getVariant());
      }
      assertEquals(
          previousExposures + 3, exposures.size(), "normal exposure deduplication applies");
      assertEquals(false, exposures.get(previousExposures).subject.attributes.get(PARAMETER));
      assertEquals(true, exposures.get(previousExposures + 1).subject.attributes.get(PARAMETER));
      assertEquals("true", exposures.get(previousExposures + 2).subject.attributes.get(PARAMETER));
      assertTelemetryCounts(previousEvents + (enabled ? 2 : 4));

      if (observeFullEvaluationData) {
        assertEquals(false, events.get(previousEvents).attrs.get(PARAMETER));
        assertEquals("true", events.get(events.size() - 1).attrs.get(PARAMETER));
        if (!enabled) {
          assertEquals(true, events.get(previousEvents + 1).attrs.get(PARAMETER));
        }
      } else {
        for (final FlagEvalEvent event : events.subList(previousEvents, events.size())) {
          assertTrue(event.attrs.isEmpty(), "normal evaluation privacy rules still apply");
        }
      }
    }
  }

  @Test
  void allowedSubjectEmitsEachSignal() throws Exception {
    start();

    evaluate("subject-1", new Value(false));

    assertTelemetryCounts(1);
    assertEquals(1, exposures.size());
    assertEquals(1, customerEvaluations.size());
  }

  @ParameterizedTest
  @MethodSource("nonOptOutValues")
  void onlyBooleanTrueSuppressesTelemetry(final Value value) throws Exception {
    start();

    evaluate("subject-1", value);

    assertTelemetryCounts(1);
    assertEquals(1, exposures.size());
  }

  static Stream<Arguments> nonOptOutValues() {
    return Stream.of(
        Arguments.of((Value) null),
        Arguments.of(new Value()),
        Arguments.of(new Value(false)),
        Arguments.of(new Value("true")),
        Arguments.of(new Value(1)),
        Arguments.of(new Value(singletonList(new Value(true)))));
  }

  @Test
  void theParameterHasNoEffectUnlessEnabled() throws Exception {
    start(new Provider.Options());

    evaluate("subject-1", new Value(true));

    assertTelemetryCounts(1);
    assertEquals(1, exposures.size());
  }

  @Test
  void theParameterIsCaseSensitiveAndDoesNotAcceptTheOldName() throws Exception {
    start();

    client.getIntegerDetails(
        "flag",
        0,
        new MutableContext("subject-1")
            .add("dd_telemetry_opt_out", true)
            .add("ddEvaluationTelemetryOptOut", true));

    assertTelemetryCounts(1);
  }

  @Test
  void providerOptionsAreCapturedAtConstruction() throws Exception {
    final Provider.Options options =
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(true);
    start(options);
    options.enableEvaluationTelemetryOptOutParameter(false);

    evaluate("subject-1", new Value(true));

    assertTelemetryCounts(0);
  }

  @Test
  void eachEvaluationUsesItsOwnDecisionWithoutChangingExistingEvents() throws Exception {
    start();

    evaluate("same-subject", new Value(false));
    evaluate("same-subject", new Value(true));
    assertTelemetryCounts(1);
    evaluate("same-subject", new Value(false));

    assertTelemetryCounts(2);
    assertEquals(1, exposures.size(), "exposure deduplication remains independent");
    assertEquals(3, customerEvaluations.size());
  }

  @Test
  void missingAndWrongTypeFlagsKeepDefaultsWithoutEmittingTelemetry() throws Exception {
    start();
    final EvaluationContext context = context("subject-1", new Value(true));

    final FlagEvaluationDetails<Integer> missing = client.getIntegerDetails("missing", 42, context);
    final FlagEvaluationDetails<String> wrongType =
        client.getStringDetails("flag", "default", context);

    assertEquals(ErrorCode.FLAG_NOT_FOUND, missing.getErrorCode());
    assertEquals(42, missing.getValue());
    assertEquals(ErrorCode.TYPE_MISMATCH, wrongType.getErrorCode());
    assertEquals("default", wrongType.getValue());
    assertTelemetryCounts(0);
    assertEquals(0, exposures.size());
    assertEquals(2, customerEvaluations.size());

    client.getIntegerDetails("missing", 42, context("subject-2", new Value(false)));
    assertTelemetryCounts(1);
  }

  @Test
  void contextMergingHonorsThePerEvaluationOverride() throws Exception {
    start();
    api.setEvaluationContext(new MutableContext().add(PARAMETER, true));

    client.getIntegerDetails("flag", 0, new ImmutableContext("subject-1"));
    evaluate("subject-2", new Value(false));

    assertTelemetryCounts(1);
    assertEquals("subject-2", events.get(0).targetingKey);
    assertEquals(2, exposures.size());
  }

  @Test
  void applicationBeforeHooksCanSupplyTheOptOutDecision() throws Exception {
    start();
    api.addHooks(
        new Hook<Object>() {
          @Override
          public Optional<EvaluationContext> before(
              final HookContext<Object> context, final Map<String, Object> hints) {
            return Optional.of(new ImmutableContext(singletonMap(PARAMETER, new Value(true))));
          }
        });

    evaluate("subject-1", new Value(false));

    assertTelemetryCounts(0);
    assertEquals(1, exposures.size());
  }

  @Test
  void applicationAfterHooksCannotUndoTheEvaluationDecision() throws Exception {
    start();
    final MutableContext invocationContext = new MutableContext("subject-1").add(PARAMETER, true);
    api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            invocationContext.add(PARAMETER, false);
          }
        });

    final FlagEvaluationDetails<Integer> details =
        client.getIntegerDetails("flag", 0, invocationContext);

    assertNull(details.getErrorCode());
    assertTelemetryCounts(0);
    assertEquals(1, exposures.size());
  }

  @Test
  void optingOutInAnAfterHookStopsTelemetryBeforeEmission() throws Exception {
    start();
    final MutableContext invocationContext = new MutableContext("subject-1").add(PARAMETER, false);
    api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            invocationContext.add(PARAMETER, true);
          }
        });

    final FlagEvaluationDetails<Integer> details =
        client.getIntegerDetails("flag", 0, invocationContext);

    assertNull(details.getErrorCode());
    assertTelemetryCounts(0);
    assertEquals(1, exposures.size());
  }

  @Test
  void aFailingBeforeHookDoesNotLeakErrorTelemetry() throws Exception {
    start();
    final MutableContext invocationContext = new MutableContext("subject-1").add(PARAMETER, true);
    api.addHooks(
        new Hook<Object>() {
          @Override
          public Optional<EvaluationContext> before(
              final HookContext<Object> context, final Map<String, Object> hints) {
            throw new IllegalStateException("application hook failed");
          }

          @Override
          public void error(
              final HookContext<Object> context,
              final Exception error,
              final Map<String, Object> hints) {
            invocationContext.add(PARAMETER, false);
          }
        });

    final FlagEvaluationDetails<Integer> details =
        client.getIntegerDetails("flag", 0, invocationContext);

    assertEquals(ErrorCode.GENERAL, details.getErrorCode());
    assertEquals(0, details.getValue());
    assertTelemetryCounts(0);
    assertEquals(0, exposures.size());
    assertEquals(1, customerEvaluations.size());
  }

  @Test
  void nestedEvaluationsKeepIndependentDecisions() throws Exception {
    start();
    api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            if ("outer".equals(context.getCtx().getTargetingKey())) {
              assertEquals(1, evaluate("inner", new Value(false)).getValue());
            }
          }
        });

    assertEquals(1, evaluate("outer", new Value(true)).getValue());

    assertTelemetryCounts(1);
    assertEquals("inner", events.get(0).targetingKey);
    assertEquals(2, exposures.size());
    assertEquals(2, customerEvaluations.size());
  }

  @Test
  void concurrentSubjectsCannotChangeEachOthersDecision() throws Exception {
    start();
    final CountDownLatch optedOutEvaluated = new CountDownLatch(1);
    final CountDownLatch allowOptedOutToFinish = new CountDownLatch(1);
    api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            if ("opted-out".equals(context.getCtx().getTargetingKey())) {
              optedOutEvaluated.countDown();
              try {
                assertTrue(allowOptedOutToFinish.await(10, SECONDS));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            }
          }
        });
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Future<FlagEvaluationDetails<Integer>> pending =
          executor.submit(() -> evaluate("opted-out", new Value(true)));
      assertTrue(optedOutEvaluated.await(10, SECONDS));
      assertEquals(1, evaluate("allowed", new Value(false)).getValue());
      allowOptedOutToFinish.countDown();
      assertNull(pending.get(10, SECONDS).getErrorCode());

      assertTelemetryCounts(1);
      assertEquals("allowed", events.get(0).targetingKey);
      assertEquals(2, exposures.size());
    } finally {
      allowOptedOutToFinish.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void independentProvidersKeepTheirOwnActivationSetting() throws Exception {
    start();
    api.setProviderAndWait(
        "unrestricted", new Provider(new Provider.Options(), new DDEvaluator(() -> {}), true));

    evaluate("opted-out", new Value(true));
    api.getClient("unrestricted")
        .getIntegerDetails("flag", 0, context("unrestricted-subject", new Value(true)));

    assertTelemetryCounts(1);
    assertEquals("unrestricted-subject", events.get(0).targetingKey);
    assertEquals(2, exposures.size());
  }

  @Test
  void optOutWorksInsideAMultiProvider() throws Exception {
    start();
    api.setProviderAndWait(
        new MultiProvider(
            singletonList(
                new Provider(
                    new Provider.Options().enableEvaluationTelemetryOptOutParameter(true),
                    new DDEvaluator(() -> {}),
                    true))));

    assertEquals(1, evaluate("opted-out", new Value(true)).getValue());
    assertTelemetryCounts(0);
    assertEquals(1, exposures.size());

    assertEquals(1, evaluate("allowed", new Value(false)).getValue());
    assertTelemetryCounts(1);
    assertEquals(2, exposures.size());
  }

  @Test
  void missingConfigurationKeepsTheDefaultWithoutEmittingTelemetry() throws Exception {
    start();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);

    final FlagEvaluationDetails<Integer> details = evaluate("subject-1", new Value(true));

    assertEquals(ErrorCode.PROVIDER_NOT_READY, details.getErrorCode());
    assertEquals(0, details.getValue());
    assertTelemetryCounts(0);
    assertEquals(0, exposures.size());
    assertEquals(1, customerEvaluations.size());
  }

  private void start() throws Exception {
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(true));
  }

  private static ServerConfiguration targetingConfiguration(
      final boolean observeFullEvaluationData) {
    final Rule rule =
        new Rule(
            singletonList(
                new ConditionConfiguration(
                    ConditionOperator.ONE_OF, PARAMETER, singletonList(true))));
    final Allocation targeted =
        new Allocation(
            "targeted",
            singletonList(rule),
            null,
            null,
            singletonList(new Split(emptyList(), "targeted", emptyMap(), 7)),
            true);
    final Allocation fallback =
        new Allocation(
            "fallback",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
            true);
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("on", new Variant("on", 1));
    variants.put("targeted", new Variant("targeted", 2));
    final Flag flag =
        new Flag("flag", true, ValueType.INTEGER, variants, asList(targeted, fallback));
    return new ServerConfiguration(
        "", "", observeFullEvaluationData, null, singletonMap("flag", flag));
  }

  private void start(final Provider.Options options) throws Exception {
    final Allocation allocation =
        new Allocation(
            "allocation",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
            true);
    final Flag flag =
        new Flag(
            "flag",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(allocation));
    FeatureFlaggingGateway.dispatch(
        new ServerConfiguration("", "", false, null, singletonMap("flag", flag)));
    api.setProviderAndWait(new Provider(options, new DDEvaluator(() -> {}), true));
    client = api.getClient();
  }

  private FlagEvaluationDetails<Integer> evaluate(final String subject, final Value optOut) {
    return client.getIntegerDetails("flag", 0, context(subject, optOut));
  }

  private static EvaluationContext context(final String subject, final Value optOut) {
    return new MutableContext(
        subject, optOut == null ? emptyMap() : singletonMap(PARAMETER, optOut));
  }

  private void assertTelemetryCounts(final int expected) {
    assertAll(
        () -> assertEquals(expected, events.size(), "evaluation EVP enqueue count"),
        () -> assertEquals(expected, metrics.get(), "OTel evaluation count"),
        () -> assertEquals(expected, spans.size(), "automatic span enrichment count"));
  }
}
