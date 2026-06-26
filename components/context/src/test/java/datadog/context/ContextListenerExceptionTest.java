package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.Test;

@ParametersAreNonnullByDefault
class ContextListenerExceptionTest extends ContextTestBase {
  @Test
  void testListenerExceptionSwallowed() {
    ContextManager.register(
        new ContextListener() {
          @Override
          public void onAttach(Context c) {
            throw new RuntimeException("listener failure");
          }

          @Override
          public void onDetach(Context c) {
            throw new RuntimeException("listener failure");
          }
        });
    Context context = root().with(STRING_KEY, "value");
    assertDoesNotThrow(
        () -> {
          try (ContextScope scope = context.attach()) {
            assertEquals(context, current());
          }
        });
  }

  @Test
  void testListenerExceptionSwallowedOnCapture() {
    ContextManager.register(
        new ContextListener() {
          @Override
          public void onCapture(Context c) {
            throw new RuntimeException("listener failure on capture");
          }
        });
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope scope = context.attach()) {
      assertDoesNotThrow(
          () -> {
            ContextContinuation continuation = context.capture();
            assertNotNull(continuation);
            assertEquals(context, continuation.context());
            continuation.release();
          });
    }
  }

  @Test
  void testListenerExceptionSwallowedOnRelease() {
    ContextManager.register(
        new ContextListener() {
          @Override
          public void onRelease(Context c) {
            throw new RuntimeException("listener failure on release");
          }
        });
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope scope = context.attach()) {
      ContextContinuation continuation = context.capture();
      assertDoesNotThrow(continuation::release);
    }
  }
}
