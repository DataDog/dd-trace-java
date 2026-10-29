package com.datadog.openfeature;

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

import com.datadog.openfeature.internal.ConfigurationService;
import com.datadog.openfeature.internal.FeatureFlagsRuntime;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.bridge.v1.SpanEnricher;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.exposure.ExposurePipeline;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Exercises real OpenFeature resolution and the provider telemetry hooks, capturing each output
 * boundary: flag evaluation events (EVP), OTel evaluation metrics, span enrichment, exposures and
 * {@link ExposureHook} evaluations.
 *
 * <p>The provider reads its settings from the real system properties and environment through {@link
 * com.datadog.openfeature.internal.bridge.v1.AgentBridges#detect()}, so the configuration scenarios
 * use the system property; the environment fallback is covered by {@code SettingsTest}.
 */
class EvaluationTelemetryOptOutTest {
  private static final String PARAMETER = "dd_evaluation_telemetry_opt_out";
  private static final String SYSTEM_PROPERTY =
      "dd.feature.flags.evaluation.telemetry.per.subject.opt.out.enabled";

  private final List<FlagEvalEvent> events = new CopyOnWriteArrayList<>();
  private final List<String> spans = new CopyOnWriteArrayList<>();
  private final List<ExposureEvent> exposures = new CopyOnWriteArrayList<>();
  private final List<ExposureHook.Evaluation> customerEvaluations = new CopyOnWriteArrayList<>();
  private final AtomicInteger metrics = new AtomicInteger();
  private final ExposureDeduplicationCache exposureCache = new ExposureDeduplicationCache();
  private final SpanEnricher spanEnricher =
      new SpanEnricher() {
        @Override
        public void serialId(final int serialId, final boolean doLog, final String targetingKey) {
          EvaluationTelemetryOptOutTest.this.spans.add("serial-id:" + targetingKey);
        }

        @Override
        public void runtimeDefault(final String flagKey, final Object defaultValue) {
          EvaluationTelemetryOptOutTest.this.spans.add("runtime-default:" + flagKey);
        }
      };
  private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  private String previousProperty;
  private FeatureFlagsRuntime runtime;
  private CapturingEvaluator evaluator;
  private Client client;

  @BeforeEach
  void setUp() throws Exception {
    this.previousProperty = System.getProperty(SYSTEM_PROPERTY);
    System.clearProperty(SYSTEM_PROPERTY);
    this.api.setEvaluationContext(new MutableContext());
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
    doAnswer(invocation -> this.metrics.incrementAndGet())
        .when(counter)
        .add(eq(1L), any(Attributes.class));
    GlobalOpenTelemetry.set(otel);
    this.runtime = capturingRuntime(new CapturingPipeline(this.events));
    this.api.addHooks(new ExposureHook(this.customerEvaluations::add));
  }

  @AfterEach
  void tearDown() {
    if (this.previousProperty == null) {
      System.clearProperty(SYSTEM_PROPERTY);
    } else {
      System.setProperty(SYSTEM_PROPERTY, this.previousProperty);
    }
    this.api.clearHooks();
    this.api.setEvaluationContext(new MutableContext());
    this.api.shutdown();
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void systemPropertyEnablesPerSubjectOptOut() throws Exception {
    System.setProperty(SYSTEM_PROPERTY, "true");
    start(new Provider.Options());
    final FlagEvaluationDetails<Integer> details = evaluate("subject-1", new Value(true));
    assertEquals(1, details.getValue());
    assertEquals("on", details.getVariant());
    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());
    evaluate("subject-2", new Value(false));
    evaluate("subject-3", null);
    assertTelemetryCounts(2);
    assertEquals(3, this.exposures.size());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"false", "invalid", ""})
  void systemPropertyDefaultsToDisabled(final String value) throws Exception {
    if (value != null) {
      System.setProperty(SYSTEM_PROPERTY, value);
    }
    start(new Provider.Options());
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(1);
    assertEquals(1, this.exposures.size());
  }

  @Test
  void explicitFalseOverridesEnabledSystemProperty() throws Exception {
    System.setProperty(SYSTEM_PROPERTY, "true");
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(false));
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(1);
    assertEquals(1, this.exposures.size());
  }

  @Test
  void explicitTrueOverridesDisabledSystemProperty() throws Exception {
    System.setProperty(SYSTEM_PROPERTY, "false");
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(true));
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());
  }

  @Test
  void systemPropertyIsCapturedAtProviderConstruction() throws Exception {
    System.setProperty(SYSTEM_PROPERTY, "true");
    final Provider.Options options = new Provider.Options();
    start(options);
    System.setProperty(SYSTEM_PROPERTY, "false");
    evaluate("subject-1", new Value(true));
    assertTelemetryCounts(0);
    this.api.shutdown();
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
    assertEquals(1, this.exposures.size());
    assertEquals(1, this.customerEvaluations.size());
    assertTelemetryCounts(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void telemetryAttributeRemainsAvailableForTargetingAndExposure(final boolean enabled)
      throws Exception {
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(enabled));

    for (final boolean observeFullEvaluationData : new boolean[] {false, true}) {
      this.evaluator.accept(targetingConfiguration(observeFullEvaluationData));
      final String subject = "subject-" + observeFullEvaluationData;
      final int previousEvents = this.events.size();
      final int previousExposures = this.exposures.size();

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
          previousExposures + 3, this.exposures.size(), "normal exposure deduplication applies");
      assertEquals(false, exposureAttribute(previousExposures));
      assertEquals(true, exposureAttribute(previousExposures + 1));
      assertEquals("true", exposureAttribute(previousExposures + 2));
      assertTelemetryCounts(previousEvents + (enabled ? 2 : 4));

      if (observeFullEvaluationData) {
        assertEquals(false, this.events.get(previousEvents).attrs.get(PARAMETER));
        assertEquals("true", this.events.get(this.events.size() - 1).attrs.get(PARAMETER));
        if (!enabled) {
          assertEquals(true, this.events.get(previousEvents + 1).attrs.get(PARAMETER));
        }
      } else {
        for (final FlagEvalEvent event : this.events.subList(previousEvents, this.events.size())) {
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
    assertEquals(1, this.exposures.size());
    assertEquals(1, this.customerEvaluations.size());
  }

  @ParameterizedTest
  @MethodSource("nonOptOutValues")
  void onlyBooleanTrueSuppressesTelemetry(final Value value) throws Exception {
    start();

    evaluate("subject-1", value);

    assertTelemetryCounts(1);
    assertEquals(1, this.exposures.size());
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
    assertEquals(1, this.exposures.size());
  }

  @Test
  void theParameterIsCaseSensitiveAndDoesNotAcceptTheOldName() throws Exception {
    start();

    this.client.getIntegerDetails(
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
    assertEquals(1, this.exposures.size(), "exposure deduplication remains independent");
    assertEquals(3, this.customerEvaluations.size());
  }

  @Test
  void missingAndWrongTypeFlagsKeepDefaultsWithoutEmittingTelemetry() throws Exception {
    start();
    final EvaluationContext context = context("subject-1", new Value(true));

    final FlagEvaluationDetails<Integer> missing =
        this.client.getIntegerDetails("missing", 42, context);
    final FlagEvaluationDetails<String> wrongType =
        this.client.getStringDetails("flag", "default", context);

    assertEquals(ErrorCode.FLAG_NOT_FOUND, missing.getErrorCode());
    assertEquals(42, missing.getValue());
    assertEquals(ErrorCode.TYPE_MISMATCH, wrongType.getErrorCode());
    assertEquals("default", wrongType.getValue());
    assertTelemetryCounts(0);
    assertEquals(0, this.exposures.size());
    assertEquals(2, this.customerEvaluations.size());

    this.client.getIntegerDetails("missing", 42, context("subject-2", new Value(false)));
    assertTelemetryCounts(1);
  }

  @Test
  void contextMergingHonorsThePerEvaluationOverride() throws Exception {
    start();
    this.api.setEvaluationContext(new MutableContext().add(PARAMETER, true));

    this.client.getIntegerDetails("flag", 0, new ImmutableContext("subject-1"));
    evaluate("subject-2", new Value(false));

    assertTelemetryCounts(1);
    assertEquals("subject-2", this.events.get(0).targetingKey);
    assertEquals(2, this.exposures.size());
  }

  @Test
  void applicationBeforeHooksCanSupplyTheOptOutDecision() throws Exception {
    start();
    this.api.addHooks(
        new Hook<Object>() {
          @Override
          public Optional<EvaluationContext> before(
              final HookContext<Object> context, final Map<String, Object> hints) {
            return Optional.of(new ImmutableContext(singletonMap(PARAMETER, new Value(true))));
          }
        });

    evaluate("subject-1", new Value(false));

    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());
  }

  @Test
  void applicationAfterHooksCannotUndoTheEvaluationDecision() throws Exception {
    start();
    final MutableContext invocationContext = new MutableContext("subject-1").add(PARAMETER, true);
    this.api.addHooks(
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
        this.client.getIntegerDetails("flag", 0, invocationContext);

    assertNull(details.getErrorCode());
    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());
  }

  @Test
  void optingOutInAnAfterHookStopsTelemetryBeforeEmission() throws Exception {
    start();
    final MutableContext invocationContext = new MutableContext("subject-1").add(PARAMETER, false);
    this.api.addHooks(
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
        this.client.getIntegerDetails("flag", 0, invocationContext);

    assertNull(details.getErrorCode());
    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());
  }

  @Test
  void aFailingBeforeHookDoesNotLeakErrorTelemetry() throws Exception {
    start();
    final MutableContext invocationContext = new MutableContext("subject-1").add(PARAMETER, true);
    this.api.addHooks(
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
        this.client.getIntegerDetails("flag", 0, invocationContext);

    assertEquals(ErrorCode.GENERAL, details.getErrorCode());
    assertEquals(0, details.getValue());
    assertTelemetryCounts(0);
    assertEquals(0, this.exposures.size());
    assertEquals(1, this.customerEvaluations.size());
  }

  @Test
  void nestedEvaluationsKeepIndependentDecisions() throws Exception {
    start();
    this.api.addHooks(
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
    assertEquals("inner", this.events.get(0).targetingKey);
    assertEquals(2, this.exposures.size());
    assertEquals(2, this.customerEvaluations.size());
  }

  @Test
  void concurrentSubjectsCannotChangeEachOthersDecision() throws Exception {
    start();
    final CountDownLatch optedOutEvaluated = new CountDownLatch(1);
    final CountDownLatch allowOptedOutToFinish = new CountDownLatch(1);
    this.api.addHooks(
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
              } catch (final InterruptedException e) {
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
      assertEquals("allowed", this.events.get(0).targetingKey);
      assertEquals(2, this.exposures.size());
    } finally {
      allowOptedOutToFinish.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void independentProvidersKeepTheirOwnActivationSetting() throws Exception {
    start();
    this.api.setProviderAndWait(
        "unrestricted",
        provider(new Provider.Options(), new CapturingEvaluator(defaultConfiguration())));

    evaluate("opted-out", new Value(true));
    this.api
        .getClient("unrestricted")
        .getIntegerDetails("flag", 0, context("unrestricted-subject", new Value(true)));

    assertTelemetryCounts(1);
    assertEquals("unrestricted-subject", this.events.get(0).targetingKey);
    assertEquals(2, this.exposures.size());
  }

  @Test
  void optOutWorksInsideAMultiProvider() throws Exception {
    start();
    this.api.setProviderAndWait(
        new MultiProvider(
            singletonList(
                provider(
                    new Provider.Options().enableEvaluationTelemetryOptOutParameter(true),
                    new CapturingEvaluator(defaultConfiguration())))));

    assertEquals(1, evaluate("opted-out", new Value(true)).getValue());
    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());

    assertEquals(1, evaluate("allowed", new Value(false)).getValue());
    assertTelemetryCounts(1);
    assertEquals(2, this.exposures.size());
  }

  @Test
  void missingConfigurationKeepsTheDefaultWithoutEmittingTelemetry() throws Exception {
    start();
    this.evaluator.accept(null);

    final FlagEvaluationDetails<Integer> details = evaluate("subject-1", new Value(true));

    assertEquals(ErrorCode.PROVIDER_NOT_READY, details.getErrorCode());
    assertEquals(0, details.getValue());
    assertTelemetryCounts(0);
    assertEquals(0, this.exposures.size());
    assertEquals(1, this.customerEvaluations.size());
  }

  private void start() throws Exception {
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(true));
  }

  private void start(final Provider.Options options) throws Exception {
    this.evaluator = new CapturingEvaluator(defaultConfiguration());
    this.api.setProviderAndWait(provider(options, this.evaluator));
    this.client = this.api.getClient();
  }

  /**
   * Creates a provider with span enrichment on, recording span enrichment to {@link #spanEnricher}.
   */
  private Provider provider(final Provider.Options options, final DDEvaluator evaluator)
      throws ReflectiveOperationException {
    final Provider provider = new Provider(options, evaluator, Boolean.TRUE);
    // No seam injects a span enricher: the provider takes it from the detected agent bridge.
    final Field enricher = SpanEnrichmentHook.class.getDeclaredField("enricher");
    enricher.setAccessible(true);
    enricher.set(provider.spanEnrichmentHook(), this.spanEnricher);
    return provider;
  }

  /** Creates a runtime only holding a flag evaluation pipeline, without starting it. */
  private static FeatureFlagsRuntime capturingRuntime(final FlagEvaluationPipeline evaluations)
      throws ReflectiveOperationException {
    final Constructor<FeatureFlagsRuntime> constructor =
        FeatureFlagsRuntime.class.getDeclaredConstructor(
            ConfigurationService.class, ExposurePipeline.class, FlagEvaluationPipeline.class);
    constructor.setAccessible(true);
    return constructor.newInstance(mock(ConfigurationService.class), null, evaluations);
  }

  private static ServerConfiguration defaultConfiguration() {
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
    return new ServerConfiguration("", "", false, null, singletonMap("flag", flag));
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

  private FlagEvaluationDetails<Integer> evaluate(final String subject, final Value optOut) {
    return this.client.getIntegerDetails("flag", 0, context(subject, optOut));
  }

  private static EvaluationContext context(final String subject, final Value optOut) {
    return new MutableContext(
        subject, optOut == null ? emptyMap() : singletonMap(PARAMETER, optOut));
  }

  private Object exposureAttribute(final int index) {
    return this.exposures.get(index).subject.attributes.get(PARAMETER);
  }

  private void assertTelemetryCounts(final int expected) {
    assertAll(
        () -> assertEquals(expected, this.events.size(), "evaluation EVP enqueue count"),
        () -> assertEquals(expected, this.metrics.get(), "OTel evaluation count"),
        () -> assertEquals(expected, this.spans.size(), "automatic span enrichment count"));
  }

  /**
   * A Datadog evaluator holding a configuration, sharing the test runtime for flag evaluation
   * events, and the test exposure cache and capture for exposures.
   */
  private final class CapturingEvaluator extends DDEvaluator {
    CapturingEvaluator(final ServerConfiguration configuration) {
      super(
          () -> {},
          AgentBridge.NONE,
          TestSettings.of("experimental.flagging.provider.span.enrichment.enabled", "true"));
      accept(configuration);
    }

    @Override
    public boolean initialize(
        final long timeout, final TimeUnit unit, final EvaluationContext context) {
      return hasConfiguration();
    }

    @Override
    FeatureFlagsRuntime runtime() {
      return EvaluationTelemetryOptOutTest.this.runtime;
    }

    @Override
    ExposureDeduplicationCache exposureCache() {
      return EvaluationTelemetryOptOutTest.this.exposureCache;
    }

    @Override
    boolean isExposureDeliveryEnabled() {
      return true;
    }

    @Override
    void recordExposure(final ExposureEvent event) {
      EvaluationTelemetryOptOutTest.this.exposures.add(event);
    }
  }

  /** A flag evaluation pipeline capturing enqueued events, never started. */
  private static final class CapturingPipeline extends FlagEvaluationPipeline {
    private final List<FlagEvalEvent> events;

    CapturingPipeline(final List<FlagEvalEvent> events) {
      super(
          1, 1, SECONDS, (route, json) -> {}, emptyMap(), new RuntimeServices(HealthMetrics.NOOP));
      this.events = events;
    }

    @Override
    public boolean hasCapacityForEnqueue() {
      return true;
    }

    @Override
    public void enqueue(final FlagEvalEvent event) {
      this.events.add(event);
    }
  }
}
