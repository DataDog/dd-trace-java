package datadog.context;

import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ContextProvidersTest {
  @Test
  void testCannotChangeBinderAfterUse() {
    Context context = root().with(STRING_KEY, "value");
    Object carrier = new Object();

    context.attachTo(carrier);
    Context.detachFrom(carrier);

    // cannot change binder at this late stage
    assertFalse(ContextBinder.allowTesting());
  }

  @Test
  void testCannotChangeManagerAfterUse() {
    Context context = root().with(STRING_KEY, "value");

    try (ContextScope ignored = context.attach()) {
      assertNotEquals(root(), Context.current());
    }

    // cannot change manager at this late stage
    assertFalse(ContextManager.allowTesting());
  }
}
