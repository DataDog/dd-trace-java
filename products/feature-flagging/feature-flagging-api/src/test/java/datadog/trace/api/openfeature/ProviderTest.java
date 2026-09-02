package datadog.trace.api.openfeature;

import static datadog.trace.api.openfeature.Provider.METADATA;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import datadog.trace.api.featureflag.flagevaluation.FlagEvaluationWriter;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.openfeature.Provider.Options;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.Features;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ProviderTest {

  private static final long EVENT_TIMEOUT_SECONDS = 10;

  private ExecutorService executor;

  @BeforeEach
  public void setup() {
    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  public void tearDown() {
    executor.shutdownNow();
    OpenFeatureAPI.getInstance().shutdown();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    FeatureFlaggingGateway.setFlagEvalWriter(null);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  public void testSetProvider() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final CompletableFuture<EventDetails> readyEvent = new CompletableFuture<>();
    api.onProviderReady(readyEvent::complete);
    api.setProvider(new Provider());

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    readyEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));
  }

  @Test
  public void testSetProviderAndWait() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final Future<?> provider = executor.submit(() -> api.setProviderAndWait(new Provider()));

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    provider.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));
  }

  @Test
  public void testSetProviderAndWaitTimeoutRecoversWhenConfigurationArrives() throws Exception {
    final CompletableFuture<EventDetails> readyEvent = new CompletableFuture<>();
    final Consumer<EventDetails> readyEventHandler = completingHandler(readyEvent);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final Client client = api.getClient();
    client.on(ProviderEvent.PROVIDER_READY, readyEventHandler);

    assertThrows(
        ProviderNotReadyError.class,
        () -> api.setProviderAndWait(new Provider(new Options().initTimeout(10, MILLISECONDS))));

    assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
    assertFalse(readyEvent.isDone());

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));

    final EventDetails eventDetails = readyEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));
    assertThat(eventDetails.getProviderName(), equalTo(METADATA));
    verify(readyEventHandler, times(1)).accept(any());
  }

  @Test
  public void testSetProviderAndWaitCompletesWhenConfigurationArrivesAtTimeoutBoundary()
      throws Exception {
    final Provider[] providerRef = new Provider[1];
    final Evaluator evaluator =
        new Evaluator() {
          private boolean hasConfiguration;

          @Override
          public boolean initialize(
              final long timeout,
              final java.util.concurrent.TimeUnit timeUnit,
              final EvaluationContext context) {
            hasConfiguration = true;
            providerRef[0].onConfigurationChange();
            return false;
          }

          @Override
          public boolean hasConfiguration() {
            return hasConfiguration;
          }

          @Override
          public void shutdown() {}

          @Override
          public <T> ProviderEvaluation<T> evaluate(
              final Class<T> target,
              final String key,
              final T defaultValue,
              final EvaluationContext context) {
            return ProviderEvaluation.<T>builder().value(defaultValue).build();
          }
        };

    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    providerRef[0] = new Provider(new Options().initTimeout(10, MILLISECONDS), evaluator);
    api.setProviderAndWait(providerRef[0]);

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));
  }

  @Test
  public void testSetProviderAndWaitFailsWhenConfigurationIsRemovedBeforeInitializationCompletes() {
    final Provider[] providerRef = new Provider[1];
    final Evaluator evaluator =
        new Evaluator() {
          private boolean hasConfiguration;

          @Override
          public boolean initialize(
              final long timeout,
              final java.util.concurrent.TimeUnit timeUnit,
              final EvaluationContext context) {
            hasConfiguration = true;
            providerRef[0].onConfigurationChange();
            hasConfiguration = false;
            providerRef[0].onConfigurationChange();
            return true;
          }

          @Override
          public boolean hasConfiguration() {
            return hasConfiguration;
          }

          @Override
          public void shutdown() {}

          @Override
          public <T> ProviderEvaluation<T> evaluate(
              final Class<T> target,
              final String key,
              final T defaultValue,
              final EvaluationContext context) {
            return ProviderEvaluation.<T>builder().value(defaultValue).build();
          }
        };

    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    providerRef[0] = new Provider(new Options().initTimeout(10, MILLISECONDS), evaluator);

    assertThrows(ProviderNotReadyError.class, () -> api.setProviderAndWait(providerRef[0]));

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
  }

  @Test
  public void testInitializationErrorDoesNotOverwriteRecoveredReadyState() throws Exception {
    final Provider[] providerRef = new Provider[1];
    final Evaluator evaluator =
        new Evaluator() {
          private boolean hasConfiguration;

          @Override
          public boolean initialize(
              final long timeout,
              final java.util.concurrent.TimeUnit timeUnit,
              final EvaluationContext context) {
            hasConfiguration = true;
            providerRef[0].onConfigurationChange();
            hasConfiguration = false;
            providerRef[0].onConfigurationChange();
            hasConfiguration = true;
            providerRef[0].onConfigurationChange();
            throw new ProviderNotReadyError(
                "Provider timed-out while waiting for initial configuration");
          }

          @Override
          public boolean hasConfiguration() {
            return hasConfiguration;
          }

          @Override
          public void shutdown() {}

          @Override
          public <T> ProviderEvaluation<T> evaluate(
              final Class<T> target,
              final String key,
              final T defaultValue,
              final EvaluationContext context) {
            return ProviderEvaluation.<T>builder().value(defaultValue).build();
          }
        };

    providerRef[0] = new Provider(new Options().initTimeout(10, MILLISECONDS), evaluator);

    assertThrows(ProviderNotReadyError.class, () -> providerRef[0].initialize(null));

    assertThat(initializationState(providerRef[0]), equalTo("READY"));
  }

  @Test
  public void testNullConfigurationAfterReadyTransitionsToErrorAndRecovers() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    api.setProviderAndWait(new Provider());
    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));

    final CompletableFuture<EventDetails> errorEvent = new CompletableFuture<>();
    final CompletableFuture<EventDetails> readyEvent = new CompletableFuture<>();
    final CompletableFuture<EventDetails> configChangedEvent = new CompletableFuture<>();
    final Consumer<EventDetails> errorEventHandler = completingHandler(errorEvent);
    final Consumer<EventDetails> readyEventHandler = completingHandler(readyEvent);
    final Consumer<EventDetails> configChangedEventHandler = completingHandler(configChangedEvent);
    client.on(ProviderEvent.PROVIDER_ERROR, errorEventHandler);
    client.on(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configChangedEventHandler);

    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    final EventDetails eventDetails = errorEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
    assertThat(eventDetails.getProviderName(), equalTo(METADATA));

    final FlagEvaluationDetails<String> evalDetails = client.getStringDetails("missing", "default");
    assertThat(evalDetails.getValue(), equalTo("default"));
    assertThat(evalDetails.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));

    client.on(ProviderEvent.PROVIDER_READY, readyEventHandler);
    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    readyEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    configChangedEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    verify(errorEventHandler, times(1)).accept(any());
    verify(readyEventHandler, times(1)).accept(any());
    verify(configChangedEventHandler, times(1)).accept(any());
  }

  @Test
  public void testFailureToLoadInternalApi() {
    @SuppressWarnings("unchecked")
    final Consumer<EventDetails> consumer = mock(Consumer.class);

    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.onProviderError(consumer);

    assertThrows(
        FatalError.class,
        () ->
            api.setProviderAndWait(
                new Provider() {
                  @Override
                  protected Class<?> loadEvaluatorClass() throws ClassNotFoundException {
                    throw new ClassNotFoundException(
                        "Class " + FeatureFlaggingGateway.class.getName() + " not found");
                  }
                }));
  }

  @Test
  public void testGetProviderHooksReturnsTelemetryHooks() {
    final Provider provider =
        new Provider(new Options().initTimeout(10, MILLISECONDS), mock(Evaluator.class));

    assertHasHook(provider, FlagEvalMetricsHook.class);
    assertHasHook(provider, FlagEvalLoggingHook.class);
  }

  @Test
  public void testGetProviderHooksSkipsFlagEvalLoggingHookOnLinkageFailure() {
    Provider provider =
        new Provider(new Options().initTimeout(10, MILLISECONDS), mock(Evaluator.class)) {
          @Override
          Hook buildFlagEvalLoggingHook() {
            throw new NoClassDefFoundError("old bootstrap");
          }
        };

    assertHasHook(provider, FlagEvalMetricsHook.class);
    assertFalse(
        provider.getProviderHooks().stream().anyMatch(FlagEvalLoggingHook.class::isInstance));
  }

  @Test
  public void testTelemetryDisabledProviderHasNoHooks() {
    final Provider provider =
        new Provider(new Options().telemetryEnabled(false), mock(Evaluator.class), Boolean.TRUE);

    assertTrue(provider.getProviderHooks().isEmpty());
    assertNull(provider.spanEnrichmentHook());
  }

  @Test
  public void testOptionsRetainDefaultTimeoutWhenOnlyTelemetryIsConfigured() {
    final Options options = new Options().telemetryEnabled(false);

    assertThat(options.getTimeout(), equalTo(30L));
    assertThat(options.getUnit(), equalTo(SECONDS));
    assertFalse(options.isTelemetryEnabled());
  }

  @Test
  public void testClientEvaluationRoutesThroughFlagEvalLoggingHook() throws Exception {
    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    final AtomicReference<FlagEvalEvent> captured = new AtomicReference<>();
    FeatureFlaggingGateway.setFlagEvalWriter(capturingWriter(captured));
    final Evaluator evaluator = mock(Evaluator.class);
    when(evaluator.initialize(eq(10L), eq(SECONDS), any())).thenReturn(true);
    when(evaluator.hasConfiguration()).thenReturn(true);
    when(evaluator.evaluate(eq(String.class), eq("logged-flag"), eq("default"), any()))
        .thenReturn(
            ProviderEvaluation.<String>builder()
                .value("value")
                .reason("STATIC")
                .variant("variant-1")
                .flagMetadata(
                    ImmutableMetadata.builder()
                        .addString("allocationKey", "allocation-1")
                        .addLong("__dd_eval_timestamp_ms", 1_700_000_000_000L)
                        .addBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA, true)
                        .build())
                .build());
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), evaluator));
    final MutableContext context = new MutableContext("user-1");
    context.add("region", "us-east-1");

    final FlagEvaluationDetails<String> details =
        api.getClient().getStringDetails("logged-flag", "default", context);

    assertThat(details.getValue(), equalTo("value"));
    final FlagEvalEvent event = captured.get();
    assertThat(event.flagKey, equalTo("logged-flag"));
    assertThat(event.variant, equalTo("variant-1"));
    assertThat(event.allocationKey, equalTo("allocation-1"));
    assertThat(event.targetingKey, equalTo("user-1"));
    assertThat(event.evalTimeMs, equalTo(1_700_000_000_000L));
    assertThat(event.attrs.get("region"), equalTo("us-east-1"));
  }

  @Test
  public void testGetProviderHooksReturnsFlagEvalMetricsHookWithAndWithoutSpanEnrichment() {
    final Evaluator evaluator = mock(Evaluator.class);
    final Provider providerWithoutSpanEnrichment =
        new Provider(new Options(), evaluator, Boolean.FALSE);
    final Provider providerWithSpanEnrichment =
        new Provider(new Options(), evaluator, Boolean.TRUE);

    assertHasFlagEvalMetricsHook(providerWithoutSpanEnrichment);
    assertHasFlagEvalMetricsHook(providerWithSpanEnrichment);
  }

  @Test
  public void testShutdownCleansUpEvaluator() throws Exception {
    final Evaluator evaluator = mock(Evaluator.class);
    when(evaluator.initialize(eq(10L), eq(MILLISECONDS), any())).thenReturn(true);
    when(evaluator.hasConfiguration()).thenReturn(true);
    final Provider provider =
        new Provider(new Options().initTimeout(10, MILLISECONDS), evaluator, Boolean.FALSE);

    provider.initialize(null);
    provider.shutdown();

    verify(evaluator).shutdown();
    assertHasHook(provider, FlagEvalMetricsHook.class);
    assertHasHook(provider, FlagEvalLoggingHook.class);
  }

  private static void assertHasFlagEvalMetricsHook(final Provider provider) {
    assertHasHook(provider, FlagEvalMetricsHook.class);
  }

  private static void assertHasHook(
      final Provider provider, final Class<? extends Hook> hookClass) {
    assertTrue(
        provider.getProviderHooks().stream().anyMatch(hookClass::isInstance),
        hookClass.getSimpleName() + " should be registered");
  }

  public interface EvaluateMethod<E> {
    FlagEvaluationDetails<E> evaluate(Features client, String flag, E defaultValue);
  }

  private static Arguments[] providerMethods() {
    return new Arguments[] {
      Arguments.of("bool", false, (EvaluateMethod<Boolean>) Features::getBooleanDetails),
      Arguments.of("string", "Hello!", (EvaluateMethod<String>) Features::getStringDetails),
      Arguments.of("int", 23, (EvaluateMethod<Integer>) Features::getIntegerDetails),
      Arguments.of("double", 3.14D, (EvaluateMethod<Double>) Features::getDoubleDetails),
      Arguments.of("object", new Value(), (EvaluateMethod<Value>) Features::getObjectDetails)
    };
  }

  @MethodSource("providerMethods")
  @ParameterizedTest
  public <E> void testProviderEvaluation(
      final String flag, final E defaultValue, final EvaluateMethod<E> method) throws Exception {
    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    final Evaluator evaluator = mock(Evaluator.class);
    when(evaluator.initialize(eq(10L), eq(SECONDS), any())).thenReturn(true);
    when(evaluator.hasConfiguration()).thenReturn(true);
    when(evaluator.evaluate(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                ProviderEvaluation.builder()
                    .value(invocation.getArgument(2))
                    .reason("MOCK")
                    .build());
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), evaluator));
    final Client client = api.getClient();
    final FlagEvaluationDetails<E> result = method.evaluate(client, flag, defaultValue);
    assertThat(result.getValue(), equalTo(defaultValue));
    assertThat(result.getReason(), equalTo("MOCK"));
    verify(evaluator, times(1)).initialize(eq(10L), eq(SECONDS), any());
    verify(evaluator, times(1))
        .evaluate(any(), eq(flag), eq(defaultValue), any(EvaluationContext.class));
  }

  private static String initializationState(final Provider provider) throws Exception {
    final Field stateField = Provider.class.getDeclaredField("initializationState");
    stateField.setAccessible(true);
    final AtomicReference<?> state = (AtomicReference<?>) stateField.get(provider);
    return state.get().toString();
  }

  @SuppressWarnings("unchecked")
  private static Consumer<EventDetails> completingHandler(
      final CompletableFuture<EventDetails> event) {
    final Consumer<EventDetails> handler = mock(Consumer.class);
    doAnswer(
            invocation -> {
              event.complete(invocation.getArgument(0));
              return null;
            })
        .when(handler)
        .accept(any());
    return handler;
  }

  private static FlagEvaluationWriter capturingWriter(final AtomicReference<FlagEvalEvent> ref) {
    return new FlagEvaluationWriter() {
      @Override
      public void enqueue(final FlagEvalEvent event) {
        ref.set(event);
      }

      @Override
      public boolean hasCapacityForEnqueue() {
        return true;
      }

      @Override
      public void countPreQueueOverflow() {}

      @Override
      public void countContextTruncated(final String reason) {}

      @Override
      public void start() {}

      @Override
      public void close() {}
    };
  }
}
