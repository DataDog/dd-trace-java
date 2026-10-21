package datadog.trace.api.openfeature;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.Environment;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.ImmutableStructure;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.tabletest.junit.TableTest;

class DebugLoggingHookTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(DebugLoggingHook.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Level previousLevel;

  @BeforeEach
  void setup() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void cleanup() {
    logger.detachAppender(appender);
    appender.stop();
    logger.setLevel(previousLevel);
  }

  @TableTest({
    "Scenario       | Level | Registered",
    "Debug enabled  | DEBUG | true      ",
    "Debug filtered | INFO  | false     ",
    "Errors only    | ERROR | false     "
  })
  void registersOnlyWhenDebugIsActive(String scenario, String level, boolean registered) {
    logger.setLevel(Level.toLevel(level));
    Provider provider =
        new Provider(new Provider.Options().initTimeout(1, MILLISECONDS), mock(Evaluator.class));
    try {
      assertEquals(
          registered,
          provider.getProviderHooks().stream().anyMatch(DebugLoggingHook.class::isInstance));
    } finally {
      provider.shutdown();
    }
  }

  @Test
  void logsRawOutcomeContextDefaultAndMetadata() {
    EvaluationContext evaluationContext =
        new MutableContext("user-123", singletonMap("country", new Value("FR")));
    HookContext<Object> context =
        HookContext.<Object>builder()
            .flagKey("checkout")
            .type(FlagValueType.STRING)
            .defaultValue("secret-default")
            .ctx(evaluationContext)
            .build();
    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("checkout")
            .value("secret-value")
            .variant("enabled")
            .reason("TARGETING_MATCH")
            .flagMetadata(
                ImmutableMetadata.builder().addString("secret-key", "secret-metadata").build())
            .build();

    DebugLoggingHook.createIfEnabled().finallyAfter(context, details, emptyMap());

    assertEquals(1, appender.list.size());
    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("flag=checkout"));
    assertTrue(message.contains("reason=TARGETING_MATCH"));
    assertTrue(message.contains("variant=enabled"));
    assertTrue(message.contains("defaultValue=secret-default"));
    assertTrue(message.contains("observedValue=secret-value"));
    assertTrue(message.contains("secret-key=secret-metadata"));
    assertTrue(message.contains("targetingKey=user-123"));
    assertTrue(message.contains("country=FR"));
  }

  @Test
  void logsRawFallbackAndErrorMessage() {
    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("missing")
            .value("secret-default")
            .reason("ERROR")
            .errorCode(ErrorCode.FLAG_NOT_FOUND)
            .errorMessage("secret-error")
            .build();

    DebugLoggingHook.createIfEnabled().finallyAfter(null, details, emptyMap());

    String message = appender.list.get(0).getFormattedMessage();
    assertTrue(message.contains("reason=ERROR"));
    assertTrue(message.contains("errorCode=FLAG_NOT_FOUND"));
    assertTrue(message.contains("errorMessage=secret-error"));
    assertTrue(message.contains("observedValue=secret-default"));
  }

  @Test
  void logsFinalFallbackWhenAnApplicationAfterHookFails() throws Exception {
    Evaluator evaluator = mock(Evaluator.class);
    when(evaluator.initialize(anyLong(), any(), any())).thenReturn(true);
    when(evaluator.hasConfiguration()).thenReturn(true);
    when(evaluator.evaluate(any(), any(), any(), any()))
        .thenReturn(
            ProviderEvaluation.builder()
                .value(true)
                .variant("enabled")
                .reason("TARGETING_MATCH")
                .build());
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    try {
      api.setProviderAndWait(
          new Provider(new Provider.Options().initTimeout(1, MILLISECONDS), evaluator));
      Client client = api.getClient();
      client.addHooks(
          new Hook<Boolean>() {
            @Override
            public void after(
                HookContext<Boolean> context,
                FlagEvaluationDetails<Boolean> details,
                Map<String, Object> hints) {
              throw new IllegalStateException("secret-application-error");
            }
          });

      assertFalse(client.getBooleanValue("checkout", false));

      assertEquals(1, appender.list.size());
      String message = appender.list.get(0).getFormattedMessage();
      assertTrue(message.contains("reason=ERROR"));
      assertTrue(message.contains("secret-application-error"));
      assertTrue(message.contains("observedValue=false"));
    } finally {
      api.shutdown();
    }
  }

  @Test
  void doesNotInspectDetailsAfterDebugIsDisabled() {
    DebugLoggingHook hook = DebugLoggingHook.createIfEnabled();
    assertNotNull(hook);
    logger.setLevel(Level.INFO);
    FlagEvaluationDetails<Object> details = mock(FlagEvaluationDetails.class);

    hook.finallyAfter(null, details, emptyMap());

    verifyNoInteractions(details);
    assertTrue(appender.list.isEmpty());
  }

  @ParameterizedTest
  @MethodSource("rawValues")
  void logsEveryValueTypeWithoutTruncation(Object value) {
    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.builder().flagKey("raw").value(value).build();
    DebugLoggingHook.createIfEnabled().finallyAfter(null, details, emptyMap());
    String message = appender.list.get(0).getFormattedMessage();
    String expected = value instanceof Value ? "tail-" + "x".repeat(4096) : value.toString();
    assertTrue(message.contains(expected), message);
  }

  static Stream<Object> rawValues() {
    return Stream.of(
        true,
        42,
        1.25,
        "untruncated-" + "x".repeat(4096),
        new Value(
            new ImmutableStructure(
                singletonMap(
                    "nested", new Value(singletonList(new Value("tail-" + "x".repeat(4096))))))),
        new Value(
            singletonList(
                new Value(
                    new ImmutableStructure(
                        singletonMap("nested", new Value("tail-" + "x".repeat(4096))))))));
  }

  @Test
  void logsProviderFallbackWithConfigurationIdentity() {
    ServerConfiguration config =
        new ServerConfiguration(
            "2026-10-21T00:00:00Z", "ufc/v1", false, new Environment("staging"), emptyMap());
    DDEvaluator evaluator = new DDEvaluator(() -> {});
    evaluator.accept(config);
    ProviderEvaluation<String> result =
        evaluator.evaluate(String.class, "missing", "default", new MutableContext("user-123"));
    assertEquals("default", result.getValue());
    String message = appender.list.get(appender.list.size() - 1).getFormattedMessage();
    assertTrue(message.contains("provider resolution:"));
    assertTrue(message.contains("errorCode=FLAG_NOT_FOUND"));
    assertTrue(message.contains("resolvedValue=default"));
    assertTrue(message.contains("configurationEnv=staging"));
    assertTrue(message.contains("configurationCreatedAt=" + config.createdAt));
  }

  @Test
  void rawErrorLoggingDoesNotChangeReturnedMetadata() {
    ServerConfiguration config = new ServerConfiguration(null, "ufc/v1", false, null, emptyMap());
    config.invalidFlags = singletonMap("broken", "parse_error");
    DDEvaluator evaluator = new DDEvaluator(() -> {});
    evaluator.accept(config);
    ProviderEvaluation<Boolean> result =
        evaluator.evaluate(Boolean.class, "broken", false, new MutableContext());
    assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
    assertNull(result.getErrorMessage());
    assertTrue(
        appender.list.stream()
            .anyMatch(
                event ->
                    event
                        .getFormattedMessage()
                        .contains("errorMessage=invalid configuration for flag broken")));
  }

  @Test
  void reportsFormattingFailureWithoutChangingEvaluation() {
    Object broken =
        new Object() {
          @Override
          public String toString() {
            throw new AssertionError("broken formatting");
          }
        };
    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.builder().flagKey("broken").value(broken).build();
    assertDoesNotThrow(
        () -> DebugLoggingHook.createIfEnabled().finallyAfter(null, details, emptyMap()));
    assertTrue(
        appender
            .list
            .get(0)
            .getFormattedMessage()
            .contains("formatting failed; raw detail unavailable"));
  }

  @Test
  void disabledResolutionDoesNotInspectContextResultOrValues() {
    logger.setLevel(Level.INFO);
    EvaluationContext context = mock(EvaluationContext.class);
    ProviderEvaluation<?> result = mock(ProviderEvaluation.class);
    Object value = mock(Object.class);
    DebugLoggingHook.resolution("flag", value, context, result, null);
    verifyNoInteractions(context, result, value);
    assertTrue(appender.list.isEmpty());
  }

  @TableTest({
    "Scenario           | Fail level check",
    "backend throws     | false           ",
    "level check throws | true            "
  })
  void loggerFailureDoesNotInterruptLifecycle(String scenario, boolean failLevelCheck)
      throws Exception {
    TurboFilter failure =
        new TurboFilter() {
          @Override
          public FilterReply decide(
              Marker marker,
              Logger target,
              Level level,
              String format,
              Object[] params,
              Throwable throwable) {
            if (target.getName().startsWith("datadog.trace.api.openfeature")
                && (failLevelCheck || format != null)) {
              throw new AssertionError("broken logger");
            }
            return FilterReply.NEUTRAL;
          }
        };
    failure.start();
    logger.getLoggerContext().addTurboFilter(failure);
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    try {
      Provider provider = new Provider(new Provider.Options().initTimeout(1, MILLISECONDS));
      assertThrows(ProviderNotReadyError.class, () -> api.setProviderAndWait(provider));
      Client client = api.getClient();
      CompletableFuture<EventDetails> recovered = new CompletableFuture<>();
      client.on(ProviderEvent.PROVIDER_READY, recovered::complete);
      ServerConfiguration config = new ServerConfiguration(null, "ufc/v1", false, null, emptyMap());
      FeatureFlaggingGateway.dispatch(config);
      recovered.get(5, TimeUnit.SECONDS);
      assertEquals(ProviderState.READY, client.getProviderState());
      assertEquals("fallback", client.getStringValue("missing", "fallback"));
      assertDoesNotThrow(api::shutdown);
    } finally {
      logger.getLoggerContext().getTurboFilterList().remove(failure);
      failure.stop();
      api.shutdown();
      FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    }
  }
}
