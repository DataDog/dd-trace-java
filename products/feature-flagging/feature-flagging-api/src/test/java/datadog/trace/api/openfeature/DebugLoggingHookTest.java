package datadog.trace.api.openfeature;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
  void logsOutcomeWithoutValuesContextOrArbitraryMetadata() {
    EvaluationContext evaluationContext = mock(EvaluationContext.class);
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
    assertFalse(message.contains("secret"));
    verifyNoInteractions(evaluationContext);
  }

  @Test
  void logsFallbackCodeWithoutPotentiallySensitiveErrorMessage() {
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
    assertFalse(message.contains("secret"));
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
      assertFalse(message.contains("secret"));
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
}
