package com.datadog.openfeature;

import static com.datadog.openfeature.Provider.METADATA;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.Provider.Options;
import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.connector.ConfigurationSource;
import com.datadog.openfeature.internal.connector.Connector;
import com.datadog.openfeature.internal.connector.EventTransport;
import com.datadog.openfeature.internal.connector.HealthMetrics;
import com.datadog.openfeature.internal.connector.SpanEnricher;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.Features;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
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
  private LocalHttpServer cdn;
  private DDEvaluator evaluator;

  @BeforeEach
  public void setup() throws IOException {
    this.executor = Executors.newSingleThreadExecutor();
    this.cdn = new LocalHttpServer();
  }

  @AfterEach
  public void tearDown() {
    this.executor.shutdownNow();
    OpenFeatureAPI.getInstance().shutdown();
    this.cdn.close();
  }

  /**
   * Creates a provider backed by a real evaluator, whose runtime polls a local CDN that has no
   * configuration. Configurations are then dispatched with {@link #dispatch}.
   */
  private Provider newProvider(final Options options) {
    return newProvider(options, Connector.NONE);
  }

  private Provider newProvider(final Options options, final Connector connector) {
    final Provider[] providerRef = new Provider[1];
    this.evaluator =
        new DDEvaluator(
            () -> providerRef[0].onConfigurationChange(),
            connector,
            TestSettings.of(
                "feature.flags.configuration.source.agentless.base.url",
                this.cdn.uri("/").toString()));
    providerRef[0] = new Provider(options, this.evaluator);
    return providerRef[0];
  }

  private Provider newProvider() {
    return newProvider(new Options().initTimeout(30, SECONDS));
  }

  private void dispatch(final ServerConfiguration configuration) {
    this.evaluator.accept(configuration);
  }

  private static ServerConfiguration emptyConfiguration() {
    return new ServerConfiguration("", "", false, null, emptyMap());
  }

  @Test
  public void testSetProvider() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final CompletableFuture<EventDetails> readyEvent = new CompletableFuture<>();
    api.onProviderReady(readyEvent::complete);
    api.setProvider(newProvider());

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    dispatch(emptyConfiguration());
    readyEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));
  }

  @Test
  public void testSetProviderAndWait() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final Provider newProvider = newProvider();
    final Future<?> provider = this.executor.submit(() -> api.setProviderAndWait(newProvider));

    final Client client = api.getClient();
    assertThat(client.getProviderState(), equalTo(ProviderState.NOT_READY));

    dispatch(emptyConfiguration());
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
        () -> api.setProviderAndWait(newProvider(new Options().initTimeout(10, MILLISECONDS))));

    assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
    assertFalse(readyEvent.isDone());

    dispatch(emptyConfiguration());

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
    final Provider provider = newProvider();
    this.executor.submit(() -> api.setProviderAndWait(provider));
    awaitInitialization();
    dispatch(emptyConfiguration());
    awaitReady(api);
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

    dispatch(null);
    final EventDetails eventDetails = errorEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.ERROR));
    assertThat(eventDetails.getProviderName(), equalTo(METADATA));

    final FlagEvaluationDetails<String> evalDetails = client.getStringDetails("missing", "default");
    assertThat(evalDetails.getValue(), equalTo("default"));
    assertThat(evalDetails.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));

    client.on(ProviderEvent.PROVIDER_READY, readyEventHandler);
    dispatch(emptyConfiguration());
    readyEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(client.getProviderState(), equalTo(ProviderState.READY));

    dispatch(emptyConfiguration());
    configChangedEvent.get(EVENT_TIMEOUT_SECONDS, SECONDS);
    verify(errorEventHandler, times(1)).accept(any());
    verify(readyEventHandler, times(1)).accept(any());
    verify(configChangedEventHandler, times(1)).accept(any());
  }

  @Test
  public void testDisabledProductFailsInitialization() {
    final Provider[] providerRef = new Provider[1];
    final DDEvaluator disabled =
        new DDEvaluator(
            () -> providerRef[0].onConfigurationChange(),
            Connector.NONE,
            TestSettings.of("feature.flags.enabled", "false"));
    providerRef[0] = new Provider(new Options().initTimeout(10, MILLISECONDS), disabled);

    final FatalError error =
        assertThrows(
            FatalError.class,
            () -> OpenFeatureAPI.getInstance().setProviderAndWait(providerRef[0]));
    assertThat(error.getCause().getMessage(), equalTo("Feature Flags is disabled"));
  }

  @Test
  public void testRemoteConfigurationWithoutAgentFailsInitialization() {
    final Provider[] providerRef = new Provider[1];
    final DDEvaluator remoteConfig =
        new DDEvaluator(
            () -> providerRef[0].onConfigurationChange(),
            Connector.NONE,
            TestSettings.of("feature.flags.configuration.source", "remote_config"));
    providerRef[0] = new Provider(new Options().initTimeout(10, MILLISECONDS), remoteConfig);

    assertThrows(
        FatalError.class, () -> OpenFeatureAPI.getInstance().setProviderAndWait(providerRef[0]));
  }

  @Test
  public void testGetProviderHooksReturnsFlagEvalMetricsHook() {
    Provider provider =
        new Provider(new Options().initTimeout(10, MILLISECONDS), mock(Evaluator.class));
    List<Hook> hooks = provider.getProviderHooks();
    // Two hooks: OTel FlagEvalMetricsHook (index 0) + FlagEvalLoggingHook (index 1)
    assertThat(hooks.size(), equalTo(2));
    assertThat(hooks.get(0) instanceof FlagEvalMetricsHook, equalTo(true));
    assertThat(hooks.get(1) instanceof FlagEvalLoggingHook, equalTo(true));
  }

  @Test
  public void testClientEvaluationRoutesThroughFlagEvalLoggingHook() throws Exception {
    final BlockingQueue<byte[]> posted = new LinkedBlockingQueue<>();
    final Provider provider =
        newProvider(
            new Options().initTimeout(10, SECONDS),
            new ProxyConnector(
                (route, json) -> {
                  if ("flagevaluation".equals(route)) {
                    posted.add(json);
                  }
                }));
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    this.executor.submit(() -> api.setProviderAndWait(provider));
    awaitInitialization();
    dispatch(loggedFlagConfiguration());
    awaitReady(api);
    final MutableContext context = new MutableContext("user-1");
    context.add("region", "us-east-1");

    final FlagEvaluationDetails<String> details =
        api.getClient().getStringDetails("logged-flag", "default", context);
    // Shutting down the provider flushes the evaluation pipeline.
    api.shutdown();

    assertThat(details.getValue(), equalTo("value"));
    final byte[] payload = posted.poll(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertTrue(payload != null, "flag evaluation payload must be posted on shutdown");
    final Map<String, Object> event = firstFlagEvaluation(payload);
    assertThat(((Map<?, ?>) event.get("flag")).get("key"), equalTo("logged-flag"));
    assertThat(((Map<?, ?>) event.get("variant")).get("key"), equalTo("variant-1"));
    assertThat(((Map<?, ?>) event.get("allocation")).get("key"), equalTo("allocation-1"));
    assertThat(event.get("targeting_key"), equalTo("user-1"));
    assertThat(event.get("evaluation_count"), equalTo(1L));
    assertThat(
        ((Map<?, ?>) ((Map<?, ?>) event.get("context")).get("evaluation")).get("region"),
        equalTo("us-east-1"));
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
    // After shutdown, getProviderHooks still returns a list with both OTel + logging hooks
    assertThat(provider.getProviderHooks().size(), equalTo(2));
  }

  private static void assertHasFlagEvalMetricsHook(final Provider provider) {
    assertTrue(
        provider.getProviderHooks().stream().anyMatch(FlagEvalMetricsHook.class::isInstance),
        "flag evaluation metrics hook should be registered");
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

  /** Waits for the evaluator to start waiting for its initial configuration. */
  private void awaitInitialization() throws InterruptedException {
    final long deadline = System.nanoTime() + SECONDS.toNanos(EVENT_TIMEOUT_SECONDS);
    while (this.evaluator.runtime() == null && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertTrue(this.evaluator.runtime() != null, "provider initialization must start");
  }

  private static void awaitReady(final OpenFeatureAPI api) throws InterruptedException {
    final long deadline = System.nanoTime() + SECONDS.toNanos(EVENT_TIMEOUT_SECONDS);
    while (api.getClient().getProviderState() != ProviderState.READY
        && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(api.getClient().getProviderState(), equalTo(ProviderState.READY));
  }

  private static ServerConfiguration loggedFlagConfiguration() {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("variant-1", new Variant("variant-1", "value"));
    final Split split = new Split(java.util.Collections.emptyList(), "variant-1", emptyMap(), null);
    final Allocation allocation =
        new Allocation("allocation-1", null, null, null, singletonList(split), Boolean.TRUE);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "logged-flag",
        new Flag("logged-flag", true, ValueType.STRING, variations, singletonList(allocation)));
    return new ServerConfiguration("", "", true, null, flags);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstFlagEvaluation(final byte[] payload) {
    final Map<String, Object> request = JsonReading.readObject(payload);
    return (Map<String, Object>) ((List<Object>) request.get("flagEvaluations")).get(0);
  }

  /** A connector only providing an event platform proxy. */
  private static final class ProxyConnector implements Connector {
    private final EventTransport proxy;

    ProxyConnector(final EventTransport proxy) {
      this.proxy = proxy;
    }

    @Override
    public String setting(final String key) {
      return null;
    }

    @Override
    public ConfigurationSource remoteConfiguration() {
      return null;
    }

    @Override
    public EventTransport eventProxy() {
      return this.proxy;
    }

    @Override
    public HealthMetrics healthMetrics() {
      return HealthMetrics.NOOP;
    }

    @Override
    public SpanEnricher spanEnricher() {
      return SpanEnricher.NOOP;
    }
  }
}
