package datadog.context;

import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;

class ContextProvidersForkedTest {
  @Test
  void testCustomBinder() {
    assertTrue(ContextBinder.allowTesting());

    Context context = root().with(STRING_KEY, "value");
    Object carrier = new Object();

    // should delegate to the default binder
    context.attachTo(carrier);
    assertNotEquals(root(), Context.from(carrier));
    assertEquals(context, Context.detachFrom(carrier));
    assertEquals(root(), Context.from(carrier));

    // now register a NOOP context binder
    ContextBinder.register(
        new ContextBinder() {
          @Override
          public Context from(@Nonnull Object carrier) {
            return root();
          }

          @Override
          public void attachTo(@Nonnull Object carrier, @Nonnull Context context) {
            // no-op
          }

          @Override
          public Context detachFrom(@Nonnull Object carrier) {
            return root();
          }
        });

    // NOOP binder, context will always be root
    context.attachTo(carrier);
    assertEquals(root(), Context.from(carrier));
    assertEquals(root(), Context.detachFrom(carrier));
  }

  @Test
  void testCustomManager() {
    assertTrue(ContextManager.allowTesting());

    Context context = root().with(STRING_KEY, "value");

    // should delegate to the default manager
    try (ContextScope ignored = context.attach()) {
      assertNotEquals(root(), Context.current());
    }

    Context swapped = context.swap();
    assertNotEquals(root(), Context.current());
    swapped.swap();

    // now register a NOOP context manager
    ContextManager.register(
        new ContextManager() {
          @Override
          public Context current() {
            return root();
          }

          @Override
          public ContextScope attach(Context context) {
            return new ContextScope() {
              @Override
              public Context context() {
                return root();
              }

              @Override
              public void close() {
                // no-op
              }
            };
          }

          @Override
          public Context swap(Context context) {
            return root();
          }
        });

    // NOOP manager, context will always be root
    try (ContextScope ignored = context.attach()) {
      assertEquals(root(), Context.current());
    }

    // NOOP manager, context will always be root
    swapped = context.swap();
    assertEquals(root(), Context.current());
    swapped.swap();
  }
}
