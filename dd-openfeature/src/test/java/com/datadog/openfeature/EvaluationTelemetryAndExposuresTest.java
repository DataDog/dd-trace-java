package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.datadog.openfeature.internal.delivery.FlagSelectionRequest;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.exposure.ExposurePipeline;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Turns evaluation telemetry and exposures off independently, per provider. */
class EvaluationTelemetryAndExposuresTest {
  private final List<FlagEvalEvent> events = new CopyOnWriteArrayList<>();
  private final List<String> spans = new CopyOnWriteArrayList<>();
  private final List<ExposureEvent> exposures = new CopyOnWriteArrayList<>();
  private final List<ExposureHook.Evaluation> customerEvaluations = new CopyOnWriteArrayList<>();
  private final AtomicInteger metrics = new AtomicInteger();
  // One cache for every provider, like the process-wide runtime.
  private final ExposureDeduplicationCache exposureCache = new ExposureDeduplicationCache();
  private final SpanEnricher spanEnricher =
      new SpanEnricher() {
        @Override
        public void serialId(final int serialId, final boolean doLog, final String targetingKey) {
          EvaluationTelemetryAndExposuresTest.this.spans.add(targetingKey);
        }

        @Override
        public void runtimeDefault(final String flagKey, final Object defaultValue) {
          EvaluationTelemetryAndExposuresTest.this.spans.add(flagKey);
        }
      };
  private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  private FeatureFlagsRuntime runtime;

  @BeforeEach
  void setUp() throws Exception {
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
    this.api.clearHooks();
    this.api.shutdown();
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  void producesEvaluationTelemetryAndExposuresByDefault() throws Exception {
    final Client client = start("live", new Provider.Options());

    final FlagEvaluationDetails<Integer> details = evaluate(client, "user-1");

    assertEquals("on", details.getVariant());
    assertTelemetryCounts(1);
    assertEquals(1, this.exposures.size());
    assertTrue(this.customerEvaluations.get(0).shouldSend());
  }

  @Test
  void evaluationTelemetryOffKeepsExposures() throws Exception {
    final Client client = start("live", new Provider.Options().evaluationTelemetryEnabled(false));

    final FlagEvaluationDetails<Integer> details = evaluate(client, "user-1");

    assertEquals("on", details.getVariant());
    assertTelemetryCounts(0);
    assertEquals(1, this.exposures.size());
    assertTrue(this.customerEvaluations.get(0).shouldSend());
  }

  @Test
  void exposuresOffKeepsEvaluationTelemetry() throws Exception {
    final Client client = start("live", new Provider.Options().exposuresEnabled(false));

    final FlagEvaluationDetails<Integer> details = evaluate(client, "user-1");

    assertEquals("on", details.getVariant());
    assertTelemetryCounts(1);
    assertTrue(this.exposures.isEmpty());
    assertFalse(this.customerEvaluations.get(0).isExposure());
    assertFalse(this.customerEvaluations.get(0).shouldSend());
  }

  @Test
  void inspectionDoesNotSuppressTheLaterExposure() throws Exception {
    final Client peek =
        start(
            "peek",
            new Provider.Options().evaluationTelemetryEnabled(false).exposuresEnabled(false));
    final Client live = start("live", new Provider.Options());

    final FlagEvaluationDetails<Integer> inspected = evaluate(peek, "user-1");
    evaluate(peek, "user-1");
    assertTelemetryCounts(0);
    assertTrue(this.exposures.isEmpty(), "the inspection sends no exposure");

    final FlagEvaluationDetails<Integer> exposed = evaluate(live, "user-1");

    assertEquals(inspected.getVariant(), exposed.getVariant());
    assertEquals(1, this.exposures.size(), "the live evaluation still sends the exposure");
    assertEquals("user-1", this.exposures.get(0).subject.id);
    assertTrue(this.customerEvaluations.get(2).shouldSend());
  }

  @Test
  void optionsAreEnabledByDefault() {
    final Provider provider = new Provider(new Provider.Options());

    assertTrue(provider.isEvaluationTelemetryEnabled());
    assertTrue(provider.isExposuresEnabled());
  }

  @Test
  void bothOptionsOffRegisterNoTelemetryOrExposureHooks() {
    final Provider provider =
        new Provider(
            new Provider.Options().evaluationTelemetryEnabled(false).exposuresEnabled(false),
            null,
            Boolean.TRUE);

    assertFalse(provider.isEvaluationTelemetryEnabled());
    assertFalse(provider.isExposuresEnabled());
    assertNoTelemetryOrExposureHooks(provider);
  }

  @Test
  void evaluationTelemetryOffRegistersOnlyTheExposureHook() {
    final Provider provider =
        new Provider(new Provider.Options().evaluationTelemetryEnabled(false), null, Boolean.TRUE);

    assertHookCount(provider, FlagEvalMetricsHook.class, 0);
    assertHookCount(provider, FlagEvalLoggingHook.class, 0);
    assertHookCount(provider, SpanEnrichmentHook.class, 0);
    assertHookCount(provider, ExposureLoggingHook.class, 1);
  }

  @Test
  void exposuresOffRegistersOnlyTheTelemetryHooks() {
    final Provider provider =
        new Provider(new Provider.Options().exposuresEnabled(false), null, Boolean.TRUE);

    assertHookCount(provider, FlagEvalMetricsHook.class, 1);
    assertHookCount(provider, FlagEvalLoggingHook.class, 1);
    assertHookCount(provider, SpanEnrichmentHook.class, 1);
    assertHookCount(provider, ExposureLoggingHook.class, 0);
  }

  @Test
  void optionsChangedAfterConstructionAreIgnored() {
    final Provider.Options options =
        new Provider.Options().evaluationTelemetryEnabled(false).exposuresEnabled(false);
    final Provider provider = new Provider(options, null, Boolean.TRUE);

    options.evaluationTelemetryEnabled(true).exposuresEnabled(true);

    assertFalse(provider.isEvaluationTelemetryEnabled());
    assertFalse(provider.isExposuresEnabled());
    assertNoTelemetryOrExposureHooks(provider);
  }

  /**
   * The evaluator the provider builds itself, rather than one injected by a test, gets the captured
   * options: exposure marking follows exposuresEnabled only, and span enrichment metadata without
   * an exposure follows evaluationTelemetryEnabled only.
   */
  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void builtEvaluatorUsesTheProviderOptions(final boolean telemetry, final boolean exposures) {
    final Provider provider =
        new Provider(
            new Provider.Options()
                .evaluationTelemetryEnabled(telemetry)
                .exposuresEnabled(exposures),
            null,
            Boolean.TRUE);
    final DDEvaluator evaluator = (DDEvaluator) provider.buildEvaluator();
    evaluator.accept(configuration());

    final ProviderEvaluation<Integer> result =
        evaluator.evaluate(Integer.class, "flag", 0, new MutableContext("user-1"));
    final Map<String, Object> metadata = result.getFlagMetadata().asUnmodifiableMap();

    assertEquals("on", result.getVariant(), "assignment is unchanged");
    assertEquals(exposures, Boolean.TRUE.equals(metadata.get(DO_LOG)), metadata::toString);
    assertEquals(exposures, metadata.containsKey(EXPOSURE_CACHE_HIT), metadata::toString);
    assertEquals(telemetry || exposures, metadata.containsKey(SPLIT_SERIAL_ID), metadata::toString);
  }

  private static void assertHookCount(
      final Provider provider, final Class<?> hookClass, final int expected) {
    long count = 0;
    for (final Hook<?> hook : provider.getProviderHooks()) {
      if (hookClass.isInstance(hook)) {
        count++;
      }
    }
    assertEquals(expected, count, hookClass::getSimpleName);
  }

  private static void assertNoTelemetryOrExposureHooks(final Provider provider) {
    for (final Hook<?> hook : provider.getProviderHooks()) {
      assertFalse(
          hook instanceof FlagEvalMetricsHook
              || hook instanceof FlagEvalLoggingHook
              || hook instanceof SpanEnrichmentHook
              || hook instanceof ExposureLoggingHook,
          () -> "unexpected provider hook " + hook.getClass().getSimpleName());
    }
  }

  private void assertTelemetryCounts(final int expected) {
    assertAll(
        () -> assertEquals(expected, this.events.size(), "evaluation EVP enqueue count"),
        () -> assertEquals(expected, this.metrics.get(), "OTel evaluation count"),
        () -> assertEquals(expected, this.spans.size(), "automatic span enrichment count"));
  }

  private static FlagEvaluationDetails<Integer> evaluate(
      final Client client, final String subject) {
    return client.getIntegerDetails("flag", 0, new MutableContext(subject));
  }

  /**
   * Registers a provider with span enrichment on under the domain. The evaluator gets the options
   * the provider captured, as the provider passes them to the evaluator it builds itself.
   */
  private Client start(final String domain, final Provider.Options options) throws Exception {
    final Provider resolved = new Provider(options, null, Boolean.TRUE);
    final CapturingEvaluator evaluator =
        new CapturingEvaluator(
            resolved.isEvaluationTelemetryEnabled(), resolved.isExposuresEnabled());
    final Provider provider = new Provider(options, evaluator, Boolean.TRUE);
    if (provider.spanEnrichmentHook() != null) {
      // No seam injects a span enricher: the provider takes it from the detected agent bridge.
      final Field enricher = SpanEnrichmentHook.class.getDeclaredField("enricher");
      enricher.setAccessible(true);
      enricher.set(provider.spanEnrichmentHook(), this.spanEnricher);
    }
    this.api.setProviderAndWait(domain, provider);
    return this.api.getClient(domain);
  }

  /** Creates a runtime only holding a flag evaluation pipeline, without starting it. */
  private static FeatureFlagsRuntime capturingRuntime(final FlagEvaluationPipeline evaluations)
      throws ReflectiveOperationException {
    final Constructor<FeatureFlagsRuntime> constructor =
        FeatureFlagsRuntime.class.getDeclaredConstructor(
            ConfigurationService.class,
            RuntimeServices.class,
            FlagSelectionRequest.class,
            FlagSelectionRequest.class,
            ExposurePipeline.class,
            FlagEvaluationPipeline.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        mock(ConfigurationService.class),
        new RuntimeServices(HealthMetrics.NOOP),
        FlagSelectionRequest.NONE,
        FlagSelectionRequest.NONE,
        null,
        evaluations);
  }

  private static ServerConfiguration configuration() {
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

  /**
   * A Datadog evaluator holding a configuration, sharing the test runtime for flag evaluation
   * events, and the test exposure cache and capture for exposures.
   */
  private final class CapturingEvaluator extends DDEvaluator {
    CapturingEvaluator(final boolean spanEnrichmentEnabled, final boolean exposuresEnabled) {
      super(
          () -> {},
          AgentBridge.NONE,
          TestSettings.of(),
          ExperimentationOptOutMode.DISABLED,
          FlagSelectionRequest.NONE,
          spanEnrichmentEnabled,
          exposuresEnabled);
      accept(configuration());
    }

    @Override
    public boolean initialize(
        final long timeout, final TimeUnit unit, final EvaluationContext context) {
      return hasConfiguration();
    }

    @Override
    FeatureFlagsRuntime runtime() {
      return EvaluationTelemetryAndExposuresTest.this.runtime;
    }

    @Override
    ExposureDeduplicationCache exposureCache() {
      return EvaluationTelemetryAndExposuresTest.this.exposureCache;
    }

    @Override
    boolean isExposureDeliveryEnabled() {
      return true;
    }

    @Override
    void recordExposure(final ExposureEvent event) {
      EvaluationTelemetryAndExposuresTest.this.exposures.add(event);
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
