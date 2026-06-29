package datadog.context;

import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static datadog.context.ContextTestBase.trackingListener;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;

class ContextProvidersForkedTest {
  @Test
  void testCustomBinder() {
    assertTrue(ContextBinder.allowTesting());

    Context context = root().with(STRING_KEY, "value");
    assertNotEquals(root(), context);

    Object carrier = new Object();

    // should delegate to the default binder
    context.attachTo(carrier);
    assertSame(context, Context.from(carrier));
    assertSame(context, Context.detachFrom(carrier));
    assertSame(root(), Context.from(carrier));

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
    assertSame(root(), Context.from(carrier));
    assertSame(root(), Context.detachFrom(carrier));
    assertSame(root(), Context.from(carrier));
  }

  @Test
  void testCustomManager() {
    assertTrue(ContextManager.allowTesting());

    Context context = root().with(STRING_KEY, "value");
    assertNotEquals(root(), context);

    // should delegate to the default manager
    try (ContextScope scope = context.attach()) {
      assertSame(context, scope.context());
      assertSame(context, Context.current());
      ContextContinuation cont = context.capture();
      assertSame(context, cont.context());
      cont.release();
    }

    Context swapped = context.swap();
    assertSame(root(), swapped);
    assertSame(context, Context.current());
    assertSame(context, swapped.swap());
    assertSame(root(), Context.current());

    // now register a NOOP context manager
    ContextManager.register(
        new ContextManager() {
          @Override
          public Context current() {
            return root();
          }

          @Override
          public ContextScope attach(Context context) {
            return new NoopContextScope(root());
          }

          @Override
          public Context swap(Context context) {
            return root();
          }

          @Override
          public ContextContinuation capture(Context context) {
            return new NoopContextContinuation(root());
          }

          @Override
          public void addListener(ContextListener listener) {}
        });

    List<String> events = new ArrayList<>();
    ContextManager.register(trackingListener(events));

    // NOOP manager, context will always be root
    try (ContextScope scope = context.attach()) {
      assertSame(root(), scope.context());
      assertSame(root(), Context.current());
      ContextContinuation cont = context.capture();
      assertSame(root(), cont.context());
      cont.release();
    }

    // NOOP manager, context will always be root
    swapped = context.swap();
    assertSame(root(), swapped);
    assertSame(root(), Context.current());
    assertSame(root(), swapped.swap());
    assertSame(root(), Context.current());

    // NOOP manager, no events emitted
    assertTrue(events.isEmpty());
  }
}
