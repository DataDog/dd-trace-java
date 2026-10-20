package datadog.trace.instrumentation.tomcat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import org.junit.jupiter.api.Test;

class TomcatServerInstrumentationTest {

  private static final class RecordingScope implements ContextScope {
    private boolean closed;

    @Override
    public Context context() {
      return null;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  @Test
  void contextTrackingAdviceCloseScopeToleratesNullScope() {
    assertDoesNotThrow(() -> TomcatServerInstrumentation.ContextTrackingAdvice.closeScope(null));
  }

  @Test
  void contextTrackingAdviceCloseScopeClosesNonNullScope() {
    RecordingScope scope = new RecordingScope();
    TomcatServerInstrumentation.ContextTrackingAdvice.closeScope(scope);
    assertTrue(scope.closed);
  }

  @Test
  void serviceAdviceCloseScopeToleratesNullScope() {
    assertDoesNotThrow(() -> TomcatServerInstrumentation.ServiceAdvice.closeScope(null));
  }

  @Test
  void serviceAdviceCloseScopeClosesNonNullScope() {
    RecordingScope scope = new RecordingScope();
    TomcatServerInstrumentation.ServiceAdvice.closeScope(scope);
    assertTrue(scope.closed);
  }
}
