package datadog.trace.api.openfeature;

import static datadog.trace.api.openfeature.Provider.METADATA;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.openfeature.Provider.Options;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.Features;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ProviderTest {

  @Captor private ArgumentCaptor<EventDetails> eventDetailsCaptor;

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
  }

  @Test
  public void testSetProvider() {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProvider(new Provider());

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    await().atMost(ofSeconds(1)).until(() -> client.getProviderState() == ProviderState.READY);
  }

  @Test
  public void testSetProviderAndWait() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final Future<?> provider = executor.submit(() -> api.setProviderAndWait(new Provider()));

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    await().atMost(ofSeconds(1)).until(() -> client.getProviderState() == ProviderState.READY);
    provider.get(1, SECONDS);
  }

  @Test
  public void testSetProviderAndWaitTimeoutRecoversWhenConfigurationArrives() {
    final Consumer<EventDetails> readyEvent = mock(Consumer.class);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final Client client = api.getClient();
    client.on(ProviderEvent.PROVIDER_READY, readyEvent);

    assertThrows(
        ProviderNotReadyError.class,
        () -> api.setProviderAndWait(new Provider(new Options().initTimeout(10, MILLISECONDS))));

    assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
    verify(readyEvent, times(0)).accept(any());

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));

    await()
        .atMost(ofSeconds(1))
        .untilAsserted(
            () -> {
              assertThat(client.getProviderState(), equalTo(ProviderState.READY));
              verify(readyEvent, times(1)).accept(eventDetailsCaptor.capture());
              final EventDetails eventDetails = eventDetailsCaptor.getValue();
              assertThat(eventDetails.getProviderName(), equalTo(METADATA));
            });
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
  public void testNullConfigurationAfterReadyTransitionsToErrorAndRecovers() {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProvider(new Provider());
    final Client client = api.getClient();

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    await().atMost(ofSeconds(1)).until(() -> client.getProviderState() == ProviderState.READY);

    final Consumer<EventDetails> errorEvent = mock(Consumer.class);
    final Consumer<EventDetails> readyEvent = mock(Consumer.class);
    final Consumer<EventDetails> configChangedEvent = mock(Consumer.class);
    client.on(ProviderEvent.PROVIDER_ERROR, errorEvent);
    client.on(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, configChangedEvent);

    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    await()
        .atMost(ofSeconds(1))
        .untilAsserted(
            () -> {
              assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
              verify(errorEvent, times(1)).accept(eventDetailsCaptor.capture());
              final EventDetails eventDetails = eventDetailsCaptor.getValue();
              assertThat(eventDetails.getProviderName(), equalTo(METADATA));
            });

    final FlagEvaluationDetails<String> evalDetails = client.getStringDetails("missing", "default");
    assertThat(evalDetails.getValue(), equalTo("default"));
    assertThat(evalDetails.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));

    client.on(ProviderEvent.PROVIDER_READY, readyEvent);
    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    await()
        .atMost(ofSeconds(1))
        .untilAsserted(
            () -> {
              assertThat(client.getProviderState(), equalTo(ProviderState.READY));
              verify(readyEvent, times(1)).accept(any());
            });

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    await()
        .atMost(ofSeconds(1))
        .untilAsserted(() -> verify(configChangedEvent, times(1)).accept(any()));
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
  public void testGetProviderHooksReturnsFlagEvalMetricsHook() {
    Provider provider =
        new Provider(new Options().initTimeout(10, MILLISECONDS), mock(Evaluator.class));
    List<Hook> hooks = provider.getProviderHooks();
    assertThat(hooks.size(), equalTo(1));
    assertThat(hooks.get(0) instanceof FlagEvalMetricsHook, equalTo(true));
  }

  @Test
  public void testShutdownCleansUpMetrics() throws Exception {
    Evaluator evaluator = mock(Evaluator.class);
    when(evaluator.initialize(eq(10L), eq(MILLISECONDS), any())).thenReturn(true);
    when(evaluator.hasConfiguration()).thenReturn(true);
    Provider provider = new Provider(new Options().initTimeout(10, MILLISECONDS), evaluator);
    provider.initialize(null);
    provider.shutdown();
    verify(evaluator).shutdown();
    // After shutdown, getProviderHooks still returns a list (hook is still present but metrics is
    // shut down)
    assertThat(provider.getProviderHooks().size(), equalTo(1));
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
}
