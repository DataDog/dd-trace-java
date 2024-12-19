package datadog.context;

import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContextBinderTest {

  @Test
  void testAttachAndDetach() {
    Context context = root().with(STRING_KEY, "value");
    Object carrier = new Object();
    assertEquals(root(), Context.from(carrier));
    context.attachTo(carrier);
    assertEquals(context, Context.from(carrier));
    // Detaching removes all context
    assertEquals(context, Context.detachFrom(carrier));
    assertEquals(root(), Context.from(carrier));
  }
}
