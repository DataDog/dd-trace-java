package datadog.context;

import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ContextBinderTest {
  @BeforeAll
  static void setup() {
    DefaultContextBinder.register();
  }

  @Test
  void testAttach() {
    Object carrier = new Object();
    Context context = Context.empty().with(STRING_KEY, "value");
    context.attachTo(carrier);
    assertEquals(context, Context.from(carrier));
  }

  @Test
  void testSupplier() {
    Object carrier = new Object();
    Context context = Context.empty().with(STRING_KEY, "value");
    Supplier<Context> supplier = () -> context;
    assertEquals(context, Context.from(carrier, supplier));
  }

  @SuppressWarnings("DataFlowIssue")
  @Test
  void testNullCarrier() {
    Object carrier = new Object();
    Context context = Context.empty().with(STRING_KEY, "value");
    // Test null carrier
    assertThrows(NullPointerException.class, () -> context.attachTo(null));
    assertThrows(NullPointerException.class, () -> Context.from(null));
    // Test null supplier
    assertThrows(NullPointerException.class, () -> Context.from(carrier, null));
  }
}
