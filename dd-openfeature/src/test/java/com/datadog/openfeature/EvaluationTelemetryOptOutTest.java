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
import static org.junit.jupiter.api.Assertions.assertSame;
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
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.multiprovider.MultiProvider;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
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
import org.tabletest.junit.TableTest;

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
  private boolean failDependencyPublisher;
  private boolean failFirstDependencyEnqueue;
  private boolean mutateDependencyRecord;

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
    assertTrue(
        this.customerEvaluations.isEmpty(),
        "a skipped before hook has no lifecycle registration for finally");
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
  void dependencyTelemetryHonorsEnabledProviderOptOut() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(true),
        dependentConfiguration());

    final FlagEvaluationDetails<Integer> optedOut =
        this.client.getIntegerDetails("root", 0, context("subject-1", new Value(true)));

    assertEquals(1, optedOut.getValue());
    assertTelemetryCounts(0);
    assertEquals(2, this.exposures.size(), "dependency and root exposures remain independent");
    assertEquals(2, this.customerEvaluations.size());
    assertTrue(
        this.customerEvaluations.stream()
            .anyMatch(evaluation -> "dependency".equals(evaluation.getDetails().getFlagKey())));
    assertTrue(
        this.customerEvaluations.stream()
            .anyMatch(evaluation -> "root".equals(evaluation.getDetails().getFlagKey())));
    assertTrue(this.customerEvaluations.stream().allMatch(ExposureHook.Evaluation::shouldSend));
  }

  @Test
  void nestedDependentRootsPublishEachCustomerExposureOnce() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());
    this.api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            if ("outer".equals(context.getCtx().getTargetingKey())) {
              assertEquals(
                  1,
                  EvaluationTelemetryOptOutTest.this
                      .client
                      .getIntegerDetails(
                          "root",
                          0,
                          EvaluationTelemetryOptOutTest.context("inner", new Value(false)))
                      .getValue());
            }
          }
        });

    assertEquals(
        1, this.client.getIntegerDetails("root", 0, context("outer", new Value(false))).getValue());

    assertEquals(4, this.customerEvaluations.size());
    assertEquals(
        2,
        this.customerEvaluations.stream()
            .filter(evaluation -> "dependency".equals(evaluation.getDetails().getFlagKey()))
            .count());
    assertEquals(
        2,
        this.customerEvaluations.stream()
            .filter(evaluation -> "root".equals(evaluation.getDetails().getFlagKey()))
            .count());
  }

  @Test
  void distinctNestedClientsReceiveOnlyTheirOwnDependencyExposures() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());
    final List<ExposureHook.Evaluation> outerEvaluations = new ArrayList<>();
    final List<ExposureHook.Evaluation> innerEvaluations = new ArrayList<>();
    final Client outer = this.api.getClient("outer-client");
    final Client inner = this.api.getClient("inner-client");
    outer.addHooks(
        new ExposureHook(outerEvaluations::add),
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            if ("outer".equals(context.getCtx().getTargetingKey())) {
              assertEquals(
                  1,
                  inner
                      .getIntegerDetails(
                          "root",
                          0,
                          EvaluationTelemetryOptOutTest.context("inner", new Value(false)))
                      .getValue());
            }
          }
        });
    inner.addHooks(new ExposureHook(innerEvaluations::add));

    assertEquals(
        1, outer.getIntegerDetails("root", 0, context("outer", new Value(false))).getValue());

    assertEquals(2, outerEvaluations.size());
    assertTrue(
        outerEvaluations.stream()
            .allMatch(evaluation -> "outer".equals(evaluation.getContext().getTargetingKey())));
    assertEquals(2, innerEvaluations.size());
    assertTrue(
        innerEvaluations.stream()
            .allMatch(evaluation -> "inner".equals(evaluation.getContext().getTargetingKey())));
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedExposureHookReceivesDependencyAndRootExactlyOnce() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());
    final List<datadog.trace.api.openfeature.ExposureHook.Evaluation> deprecated =
        new ArrayList<>();
    this.api.addHooks(new datadog.trace.api.openfeature.ExposureHook(deprecated::add));

    assertEquals(
        1,
        this.client
            .getIntegerDetails("root", 0, context("deprecated", new Value(false)))
            .getValue());

    assertEquals(2, deprecated.size());
    assertEquals(
        1,
        deprecated.stream()
            .filter(evaluation -> "dependency".equals(evaluation.getDetails().getFlagKey()))
            .count());
    assertEquals(
        1,
        deprecated.stream()
            .filter(evaluation -> "root".equals(evaluation.getDetails().getFlagKey()))
            .count());
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedExposureHookDoesNotPublishDependenciesFromFailedOrRejectedGraphs()
      throws Exception {
    final List<datadog.trace.api.openfeature.ExposureHook.Evaluation> deprecated =
        new ArrayList<>();
    this.api.addHooks(new datadog.trace.api.openfeature.ExposureHook(deprecated::add));
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        failedDependencyConfiguration());

    final FlagEvaluationDetails<Integer> failed =
        this.client.getIntegerDetails("root", 0, context("failed", new Value(false)));

    assertEquals(ErrorCode.GENERAL, failed.getErrorCode());
    assertEquals(1, deprecated.size());
    assertEquals("root", deprecated.get(0).getDetails().getFlagKey());

    this.api.shutdown();
    deprecated.clear();
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        rejectedDependencyConfiguration());
    this.api.addHooks(new datadog.trace.api.openfeature.ExposureHook(deprecated::add));

    final FlagEvaluationDetails<Integer> rejected =
        this.client.getIntegerDetails("root", 0, context("rejected", new Value(false)));

    assertNull(rejected.getErrorCode());
    assertEquals(1, deprecated.size());
    assertEquals("root", deprecated.get(0).getDetails().getFlagKey());
  }

  @Test
  @SuppressWarnings("deprecation")
  void deprecatedExposureHookCleansUpNestedLifecycles() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());
    final List<datadog.trace.api.openfeature.ExposureHook.Evaluation> deprecated =
        new ArrayList<>();
    this.api.addHooks(
        new datadog.trace.api.openfeature.ExposureHook(deprecated::add),
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            if ("outer".equals(context.getCtx().getTargetingKey())) {
              assertEquals(
                  1,
                  EvaluationTelemetryOptOutTest.this
                      .client
                      .getIntegerDetails(
                          "root",
                          0,
                          EvaluationTelemetryOptOutTest.context("inner", new Value(false)))
                      .getValue());
            }
          }
        });

    assertEquals(
        1, this.client.getIntegerDetails("root", 0, context("outer", new Value(false))).getValue());

    assertEquals(4, deprecated.size());
    assertEquals(
        2,
        deprecated.stream()
            .filter(evaluation -> "inner".equals(evaluation.getContext().getTargetingKey()))
            .count());
    assertEquals(
        2,
        deprecated.stream()
            .filter(evaluation -> "outer".equals(evaluation.getContext().getTargetingKey()))
            .count());
  }

  @Test
  void enabledProviderOptOutIncludesDependencyTelemetryWhenSubjectDoesNotOptOut() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(true),
        dependentConfiguration());

    for (final Value optOut : asList(null, new Value(false))) {
      final String subject = optOut == null ? "absent" : "false";
      final FlagEvaluationDetails<Integer> included =
          this.client.getIntegerDetails("root", 0, context(subject, optOut));

      assertEquals(1, included.getValue());
    }

    assertEquals(4, this.events.size(), "dependency and root events for both subjects");
    assertEquals("dependency", this.events.get(0).flagKey);
    assertEquals("root", this.events.get(1).flagKey);
    assertEquals("dependency", this.events.get(2).flagKey);
    assertEquals("root", this.events.get(3).flagKey);
    assertEquals(4, this.metrics.get(), "dependency and root metrics for both subjects");
    assertEquals(4, this.exposures.size());
  }

  @Test
  void dependencyTelemetryIncludesMetricsWhenProviderOptOutIsDisabled() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());

    final FlagEvaluationDetails<Integer> included =
        this.client.getIntegerDetails("root", 0, context("subject-2", new Value(true)));

    assertEquals(1, included.getValue());
    assertEquals(2, this.events.size(), "dependency and root evaluation events");
    assertEquals("dependency", this.events.get(0).flagKey, "dependency publishes before root");
    assertEquals("root", this.events.get(1).flagKey);
    assertEquals(2, this.metrics.get(), "dependency and root OTel metrics");
    assertEquals(1, this.spans.size(), "only the ordinary root span hook runs");
    assertEquals(2, this.exposures.size());
  }

  @Test
  void dependencyTelemetryReusesOneDetachedContextSnapshotPerBatch() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        twoDependencyConfiguration(true));
    final MutableContext context = new MutableContext("subject");
    context.add("region", "us-east-1");

    final FlagEvaluationDetails<Integer> included =
        this.client.getIntegerDetails("root", 0, context);
    context.add("region", "eu-west-1");

    assertEquals(1, included.getValue());
    assertEquals(3, this.events.size(), "two dependencies and the root evaluation");
    assertEquals("first", this.events.get(0).flagKey);
    assertEquals("second", this.events.get(1).flagKey);
    assertSame(
        this.events.get(0).attrs,
        this.events.get(1).attrs,
        "dependency events in one batch must reuse the detached snapshot");
    assertEquals("us-east-1", this.events.get(0).attrs.get("region"));
  }

  @Test
  void dependencyAndRootTelemetryRetainTheEvaluationConsentSnapshot() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        twoDependencyConfiguration(false));
    final MutableContext context = new MutableContext("subject").add("region", "us-east-1");
    this.api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> hookContext,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            EvaluationTelemetryOptOutTest.this.evaluator.accept(twoDependencyConfiguration(true));
          }
        });

    final FlagEvaluationDetails<Integer> first = this.client.getIntegerDetails("root", 0, context);

    assertNull(first.getErrorCode());
    assertEquals(1, first.getValue());
    assertEquals(3, this.events.size(), "two dependencies and the root evaluation");
    for (final FlagEvalEvent event : this.events) {
      assertEquals(false, event.observeFullEvaluationData);
      assertTrue(event.attrs.isEmpty());
    }

    final FlagEvaluationDetails<Integer> second = this.client.getIntegerDetails("root", 0, context);

    assertNull(second.getErrorCode());
    assertEquals(1, second.getValue());
    assertEquals(6, this.events.size());
    for (final FlagEvalEvent event : this.events.subList(3, 6)) {
      assertEquals(true, event.observeFullEvaluationData);
      assertEquals("us-east-1", event.attrs.get("region"));
    }
  }

  @TableTest({
    "initial | after",
    "false   | true ",
    "true    | false"
  })
  void dependencyAndRootTelemetryShareAfterHookOptOutBoundary(
      final boolean initial, final boolean after) throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(true),
        dependentConfiguration());
    final MutableContext context = new MutableContext("subject").add(PARAMETER, initial);
    this.api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> hookContext,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            context.add(PARAMETER, after);
          }
        });

    final FlagEvaluationDetails<Integer> details =
        this.client.getIntegerDetails("root", 0, context);

    assertNull(details.getErrorCode());
    assertEquals(1, details.getValue());
    assertTelemetryCounts(0);
    assertEquals(2, this.exposures.size(), "evaluation opt-out does not suppress exposures");
  }

  @Test
  void directProviderCallEmitsNeitherRootNorDependencyTelemetry() throws Exception {
    this.evaluator = new CapturingEvaluator(dependentConfiguration());
    final Provider provider =
        provider(
            new Provider.Options().enableEvaluationTelemetryOptOutParameter(false), this.evaluator);
    final MutableContext context = new MutableContext("subject");
    provider.initialize(context);

    final ProviderEvaluation<Integer> details = provider.getIntegerEvaluation("root", 0, context);

    assertNull(details.getErrorCode());
    assertEquals(1, details.getValue());
    assertTelemetryCounts(0);
    assertTrue(this.exposures.isEmpty());
    assertTrue(this.customerEvaluations.isEmpty());
    provider.shutdown();
  }

  @Test
  void failingAfterHookDiscardsBufferedDependencyExposures() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());
    this.api.addHooks(
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            throw new IllegalStateException("application hook failed");
          }
        });

    final FlagEvaluationDetails<Integer> details =
        this.client.getIntegerDetails("root", 0, context("subject", new Value(false)));

    assertEquals(ErrorCode.GENERAL, details.getErrorCode());
    assertEquals(0, details.getValue());
    assertEquals(2, this.events.size(), "dependency and failed root telemetry");
    assertEquals("dependency", this.events.get(0).flagKey);
    assertEquals("root", this.events.get(1).flagKey);
    assertEquals(0, this.exposures.size());

    this.client.getIntegerDetails("root", 0, context("nested-subject", new Value(false)));
    assertEquals(4, this.events.size(), "the failed lifecycle must not retain its scope");
    assertEquals("dependency", this.events.get(2).flagKey);
    assertEquals("root", this.events.get(3).flagKey);
  }

  @Test
  void failingDependencyPublisherDoesNotChangeTheRootOrExposures() throws Exception {
    this.failDependencyPublisher = true;
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());

    final FlagEvaluationDetails<Integer> details =
        this.client.getIntegerDetails("root", 0, context("subject", new Value(false)));

    assertEquals(1, details.getValue());
    assertEquals(1, this.events.size(), "only root telemetry survives publisher failure");
    assertEquals(1, this.metrics.get(), "only root metrics survive publisher failure");
    assertEquals(2, this.exposures.size(), "publisher failure does not affect exposures");
  }

  @Test
  void oneDependencyEnqueueFailureDoesNotSuppressLaterTelemetry() throws Exception {
    this.failFirstDependencyEnqueue = true;
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        twoDependencyConfiguration(false));

    final FlagEvaluationDetails<Integer> details =
        this.client.getIntegerDetails("root", 0, context("subject", new Value(false)));

    assertNull(details.getErrorCode());
    assertEquals(1, details.getValue());
    assertEquals(2, this.events.size(), "the second dependency and root must still publish");
    assertEquals("second", this.events.get(0).flagKey);
    assertEquals("root", this.events.get(1).flagKey);
    assertEquals(3, this.metrics.get(), "dependency metric publication is independent");
    assertTrue(this.exposures.isEmpty(), "telemetry failure must not create exposures");
  }

  @Test
  void dependencyRecordCannotBeMutatedByItsPublisher() throws Exception {
    this.mutateDependencyRecord = true;
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());

    final FlagEvaluationDetails<Integer> details =
        this.client.getIntegerDetails("root", 0, context("subject", new Value(false)));

    assertEquals(1, details.getValue());
    assertEquals(1, this.events.size(), "immutable record rejects publisher mutation");
    assertEquals(2, this.exposures.size(), "mutation attempt does not affect exposures");
  }

  @Test
  void parallelDependentRootsKeepIndependentReturnedBatches() throws Exception {
    start(
        new Provider.Options().enableEvaluationTelemetryOptOutParameter(false),
        dependentConfiguration());
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      final Future<FlagEvaluationDetails<Integer>> first =
          executor.submit(
              () -> this.client.getIntegerDetails("root", 0, context("first", new Value(false))));
      final Future<FlagEvaluationDetails<Integer>> second =
          executor.submit(
              () -> this.client.getIntegerDetails("root", 0, context("second", new Value(false))));

      assertEquals(1, first.get(5, SECONDS).getValue());
      assertEquals(1, second.get(5, SECONDS).getValue());
    } finally {
      executor.shutdownNow();
    }
    assertEquals(4, this.events.size(), "dependency and root for each evaluation");
    assertEquals(4, this.metrics.get());
    assertEquals(4, this.exposures.size());
    assertEquals(4, this.customerEvaluations.size());
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

  @TableTest({
    "scenario         | mode                    | exclude experimentation | suppress telemetry",
    "neither          | DISABLED                | false                   | false             ",
    "telemetry only   | DISABLED                | false                   | true              ",
    "experiments only | IMMEDIATE               | true                    | false             ",
    "both             | IMMEDIATE               | true                    | true              ",
    "retained future  | FUTURE_EXPERIMENTS_ONLY | false                   | false             ",
    "retained private | FUTURE_EXPERIMENTS_ONLY | false                   | true              "
  })
  void experimentationAndEvaluationTelemetryControlsRemainIndependent(
      final ExperimentationOptOutMode mode,
      final boolean excludeExperimentation,
      final boolean suppressTelemetry)
      throws Exception {
    this.evaluator =
        new CapturingEvaluator(
            experimentationConfiguration("2026-10-19T11:59:59.999999999Z"), mode);
    this.api.setProviderAndWait(
        provider(
            new Provider.Options()
                .experimentationOptOutMode(mode)
                .enableEvaluationTelemetryOptOutParameter(suppressTelemetry),
            this.evaluator));
    final FlagEvaluationDetails<Integer> details =
        this.api
            .getClient()
            .getIntegerDetails(
                "flag",
                0,
                new MutableContext("subject-1")
                    .add(PARAMETER, true)
                    .add("dd_experimentation_opt_out_at", "2026-10-19T12:00:00Z"));

    assertNull(details.getErrorCode());
    assertEquals(excludeExperimentation ? 2 : 1, details.getValue());
    assertEquals(excludeExperimentation ? 0 : 1, this.exposures.size());
    assertTelemetryCounts(suppressTelemetry ? 0 : 1);
    assertEquals(1, this.customerEvaluations.size());
  }

  @TableTest({
    "mode                    | activation           | excluded",
    "IMMEDIATE               | 2026-10-19T11:59:59Z | true    ",
    "FUTURE_EXPERIMENTS_ONLY | 2026-10-19T12:00:00Z | true    ",
    "FUTURE_EXPERIMENTS_ONLY | 2026-10-19T11:59:59Z | false   "
  })
  void optOutDoesNotConsumeOrClearExposureDeduplication(
      final ExperimentationOptOutMode mode, final String activation, final boolean excluded)
      throws Exception {
    this.evaluator = new CapturingEvaluator(experimentationConfiguration(activation), mode);
    this.api.setProviderAndWait(
        provider(new Provider.Options().experimentationOptOutMode(mode), this.evaluator));
    final Client client = this.api.getClient();
    final MutableContext optedOut =
        new MutableContext("subject").add("dd_experimentation_opt_out_at", "2026-10-19T12:00:00Z");
    final MutableContext participating = new MutableContext("subject");
    assertEquals(excluded ? 2 : 1, client.getIntegerValue("flag", 0, optedOut));
    assertEquals(excluded ? 0 : 1, this.exposures.size());
    assertEquals(1, client.getIntegerValue("flag", 0, participating));
    assertEquals(1, this.exposures.size(), "exclusion must not consume the first exposure");
    assertEquals(excluded ? 2 : 1, client.getIntegerValue("flag", 0, optedOut));
    assertEquals(1, client.getIntegerValue("flag", 0, participating));
    assertEquals(1, this.exposures.size(), "exclusion must not clear the previous exposure");
  }

  private static ServerConfiguration experimentationConfiguration(final String activation) {
    final ServerConfiguration configuration = defaultConfiguration();
    final Flag flag = configuration.flags.get("flag");
    final Allocation original = flag.allocations.get(0);
    final Allocation experiment =
        new Allocation(
            original.key,
            original.rules,
            original.startAt,
            original.endAt,
            original.splits,
            original.doLog,
            java.time.Instant.parse(activation));
    final Allocation ordinary =
        new Allocation(
            "ordinary",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "off", emptyMap(), 8)),
            false);
    final Map<String, Variant> variants = new HashMap<>(flag.variations);
    variants.put("off", new Variant("off", 2));
    final Flag withFallback =
        new Flag("flag", true, ValueType.INTEGER, variants, asList(experiment, ordinary));
    return new ServerConfiguration("", "", false, null, singletonMap("flag", withFallback));
  }

  private void start() throws Exception {
    start(new Provider.Options().enableEvaluationTelemetryOptOutParameter(true));
  }

  private void start(final Provider.Options options) throws Exception {
    start(options, defaultConfiguration());
  }

  private void start(final Provider.Options options, final ServerConfiguration configuration)
      throws Exception {
    this.evaluator = new CapturingEvaluator(configuration);
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

  private static ServerConfiguration dependentConfiguration() {
    final Allocation dependencyAllocation =
        new Allocation(
            "dependency-allocation",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
            true);
    final Flag dependency =
        new Flag(
            "dependency",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(dependencyAllocation));
    final Rule rule =
        new Rule(
            singletonList(
                new ConditionConfiguration(
                    ConditionOperator.ONE_OF, null, "dependency", singletonList("on"))));
    final Allocation rootAllocation =
        new Allocation(
            "root-allocation",
            singletonList(rule),
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
            true);
    final Flag root =
        new Flag(
            "root",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(rootAllocation));
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", dependency);
    flags.put("root", root);
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static ServerConfiguration failedDependencyConfiguration() {
    final Rule rule =
        new Rule(
            singletonList(
                new ConditionConfiguration(
                    ConditionOperator.ONE_OF, null, "missing", singletonList("on"))));
    final Allocation allocation =
        new Allocation(
            "root-allocation",
            singletonList(rule),
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
            true);
    final Flag root =
        new Flag(
            "root",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(allocation));
    return new ServerConfiguration("", "", false, null, singletonMap("root", root));
  }

  private static ServerConfiguration rejectedDependencyConfiguration() {
    final ServerConfiguration configuration = dependentConfiguration();
    final Flag root = configuration.flags.get("root");
    final ConditionConfiguration rejected =
        new ConditionConfiguration(
            ConditionOperator.ONE_OF, null, "dependency", singletonList("off"));
    final Allocation allocation =
        new Allocation(
            "rejected",
            singletonList(new Rule(singletonList(rejected))),
            null,
            null,
            root.allocations.get(0).splits,
            true);
    final Map<String, Flag> flags = new HashMap<>(configuration.flags);
    flags.put(
        "root",
        new Flag(
            root.key,
            root.enabled,
            root.variationType,
            root.variations,
            singletonList(allocation)));
    return new ServerConfiguration("", "", false, null, flags);
  }

  private static ServerConfiguration twoDependencyConfiguration(
      final boolean observeFullEvaluationData) {
    final Map<String, Flag> flags = new HashMap<>();
    final List<ConditionConfiguration> conditions = new ArrayList<>();
    for (final String key : asList("first", "second")) {
      final Allocation dependencyAllocation =
          new Allocation(
              key + "-allocation",
              null,
              null,
              null,
              singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
              false);
      flags.put(
          key,
          new Flag(
              key,
              true,
              ValueType.INTEGER,
              singletonMap("on", new Variant("on", 1)),
              singletonList(dependencyAllocation)));
      conditions.add(
          new ConditionConfiguration(ConditionOperator.ONE_OF, null, key, singletonList("on")));
    }
    final Allocation rootAllocation =
        new Allocation(
            "root-allocation",
            singletonList(new Rule(conditions)),
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 7)),
            false);
    flags.put(
        "root",
        new Flag(
            "root",
            true,
            ValueType.INTEGER,
            singletonMap("on", new Variant("on", 1)),
            singletonList(rootAllocation)));
    return new ServerConfiguration("", "", observeFullEvaluationData, null, flags);
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
      this(configuration, ExperimentationOptOutMode.DISABLED);
    }

    CapturingEvaluator(
        final ServerConfiguration configuration, final ExperimentationOptOutMode mode) {
      super(
          () -> {},
          AgentBridge.NONE,
          TestSettings.of("experimental.flagging.provider.span.enrichment.enabled", "true"),
          mode);
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

    @Override
    void publishDependencyEvaluations(
        final DependencyEvaluationTrace trace,
        final EvaluationContext context,
        final com.datadog.openfeature.internal.flagevaluation.FlagEvaluationWriter writer) {
      if (EvaluationTelemetryOptOutTest.this.failDependencyPublisher) {
        throw new IllegalStateException("publisher failed");
      }
      if (EvaluationTelemetryOptOutTest.this.mutateDependencyRecord) {
        trace.evaluations.get(0).metadata.asUnmodifiableMap().clear();
      }
      super.publishDependencyEvaluations(trace, context, writer);
    }

    @Override
    void recordDependencyMetrics(
        final DependencyEvaluationTrace trace, final FlagEvalMetrics dependencyMetrics) {
      if (EvaluationTelemetryOptOutTest.this.failDependencyPublisher) {
        throw new IllegalStateException("publisher failed");
      }
      super.recordDependencyMetrics(trace, dependencyMetrics);
    }
  }

  /** A flag evaluation pipeline capturing enqueued events, never started. */
  private final class CapturingPipeline extends FlagEvaluationPipeline {
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
      if (EvaluationTelemetryOptOutTest.this.failFirstDependencyEnqueue
          && !"root".equals(event.flagKey)) {
        EvaluationTelemetryOptOutTest.this.failFirstDependencyEnqueue = false;
        throw new IllegalStateException("first dependency enqueue failed");
      }
      this.events.add(event);
    }
  }
}
