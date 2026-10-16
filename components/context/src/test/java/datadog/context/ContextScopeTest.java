package datadog.context;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextScopeTest {

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
  void staticCloseToleratesNullScope() {
    assertDoesNotThrow(() -> ContextScope.close(null));
  }

  @Test
  void staticCloseClosesNonNullScope() {
    RecordingScope scope = new RecordingScope();
    ContextScope.close(scope);
    assertTrue(scope.closed);
  }
}
