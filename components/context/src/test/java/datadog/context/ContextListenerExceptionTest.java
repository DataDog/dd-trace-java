package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

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
      ContextContinuation[] ref = {null};
      assertDoesNotThrow(() -> ref[0] = context.capture());
      assertNotNull(ref[0]);
      assertEquals(context, ref[0].context());
      ref[0].release();
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
