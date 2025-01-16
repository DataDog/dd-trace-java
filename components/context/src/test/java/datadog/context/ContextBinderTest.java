package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.detachFrom;
import static datadog.context.Context.from;
import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextBinderTest {
  @BeforeEach
  void setUp() {
    assertEquals(root(), current(), "No context is expected to be set");
  }

  @Test
  void testAttachAndDetach() {
    // Setting up test
    Context context = root().with(STRING_KEY, "value");
    Object carrier = new Object();
    assertEquals(root(), from(carrier), "Carrier expected to hold root context by default");
    // Attaching context
    context.attachTo(carrier);
    assertEquals(context, from(carrier), "Carrier expected to hold new context");
    assertEquals(root(), current(), "Current execution expected to stay root");
    // Detaching removes all context
    assertEquals(context, detachFrom(carrier), "Detached context expected to new context");
    assertEquals(root(), detachFrom(carrier), "Carrier expected to hold no more context");
    assertEquals(root(), from(carrier), "Carrier expected to hold no more context");
  }

  @Test
  void testNullCarrier() {
    assertThrows(
        NullPointerException.class,
        () -> assertEquals(root(), from(null), "Binder expected to return non-null context"),
        "Null carrier expected to hold root context");
    assertThrows(
        NullPointerException.class,
        () -> assertEquals(root(), detachFrom(null), "Binder expected to return non-null context"),
        "Null carrier expected to hold root context");
  }

  @Test
  void testNullContext() {
    ContextBinder binder = ContextProviders.binder();
    Object carrier = new Object();
    assertThrows(
        NullPointerException.class,
        () -> binder.attachTo(carrier, null),
        "Attaching null context not expected to throw");
  }
}
