package datadog.trace.api.openfeature;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ProviderEvaluation;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Exercises the cached debug gate in a fresh JVM with the application logger at DEBUG. */
class DebugLoggingDisabledForkedTest {
  static {
    System.setProperty("dd.trace.debug", "false");
  }

  @Test
  void skipsHookRegistrationAndRawInspection() {
    Logger backend = (Logger) LoggerFactory.getLogger("datadog.trace.api.openfeature");
    Level previousLevel = backend.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    backend.setLevel(Level.DEBUG);
    backend.addAppender(appender);
    try {
      Provider provider =
          new Provider(new Provider.Options().initTimeout(1, MILLISECONDS), mock(Evaluator.class));
      try {
        assertFalse(
            provider.getProviderHooks().stream().anyMatch(DebugLoggingHook.class::isInstance));
        assertNull(DebugLoggingHook.createIfEnabled());
        EvaluationContext context = mock(EvaluationContext.class);
        ProviderEvaluation<?> result = mock(ProviderEvaluation.class);
        FlagEvaluationDetails<Object> details = mock(FlagEvaluationDetails.class);
        Object value = mock(Object.class);
        HookContext<Object> hookContext =
            HookContext.builder()
                .flagKey("flag")
                .type(FlagValueType.OBJECT)
                .ctx(context)
                .defaultValue(value)
                .build();

        DebugLoggingHook.resolution("flag", value, context, result, null);
        DebugLoggingHook.resolutionError("flag", "ERROR", "raw error");
        new DebugLoggingHook().finallyAfter(hookContext, details, emptyMap());

        verifyNoInteractions(context, result, details, value);
      } finally {
        provider.shutdown();
      }
      assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.DEBUG));
    } finally {
      backend.detachAppender(appender);
      appender.stop();
      backend.setLevel(previousLevel);
    }
  }
}
