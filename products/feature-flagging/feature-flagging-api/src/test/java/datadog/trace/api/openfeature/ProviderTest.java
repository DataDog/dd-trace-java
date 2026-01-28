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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.openfeature.Provider.Options;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.Features;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  public void testSetProviderAndWait() {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    executor.submit(() -> api.setProviderAndWait(new Provider()));

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));
    await().atMost(ofSeconds(1)).until(() -> client.getProviderState() == ProviderState.READY);
  }

  @Test
  public void testSetProviderAndWaitTimeout() {
    final Consumer<EventDetails> readyEvent = mock(Consumer.class);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final Client client = api.getClient();
    client.on(ProviderEvent.PROVIDER_READY, readyEvent);

    // we time out after 10 millis without receiving the initial config
    assertThrows(
        ProviderNotReadyError.class,
        () -> api.setProviderAndWait(new Provider(new Options().initTimeout(10, MILLISECONDS))));

    // ready has not yet been called
    verify(readyEvent, times(0)).accept(any());

    // dispatch an initial configuration
    FeatureFlaggingGateway.dispatch(mock(ServerConfiguration.class));

    // ready is called after receiving the configuration
    await()
        .atMost(ofSeconds(1))
        .untilAsserted(
            () -> {
              verify(readyEvent, times(1)).accept(eventDetailsCaptor.capture());
              final EventDetails details = eventDetailsCaptor.getValue();
              assertThat(details.getProviderName(), equalTo(METADATA));
            });
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
    when(evaluator.initialize(anyLong(), any(), any())).thenReturn(true);
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
}
