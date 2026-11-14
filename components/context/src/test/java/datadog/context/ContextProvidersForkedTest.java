package datadog.context;

import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static datadog.context.ContextTestBase.trackingListener;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // should delegate flag changes and suppression to the default manager
    ContextManager manager = ContextProviders.manager();
    assertTrue(manager.isCaptureEnabled());
    manager.setCaptureEnabled(false);
    assertFalse(manager.isCaptureEnabled());
    assertNotEquals(context, context.checkpoint());
    assertSame(root().capture(), context.capture());
    manager.setCaptureEnabled(true);
    assertTrue(manager.isCaptureEnabled());

    // should delegate to the default manager
    try (ContextScope scope = context.attach()) {
      assertSame(context, scope.context());
      assertSame(context, Context.current());
      ContextContinuation cont = context.capture();
      assertSame(context, cont.context());
      cont.release();
    }

    ContextHandle swapped = context.swap();
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
          public ContextScope attach(@Nonnull Context context) {
            return new NoopContextScope(root());
          }

          @Override
          public ContextHandle swap(@Nonnull ContextHandle context) {
            return root();
          }

          @Override
          public ContextHandle checkpoint(@Nonnull Context context) {
            return context;
          }

          @Override
          public ContextContinuation capture(@Nonnull Context context) {
            return new NoopContextContinuation(root());
          }

          @Override
          public boolean isCaptureEnabled() {
            return true;
          }

          @Override
          public void setCaptureEnabled(boolean enabled) {}

          @Override
          public void addListener(@Nonnull ContextListener listener) {}
        });

    ContextTestBase.TrackingListener listener = trackingListener();
    ContextManager.register(listener);

    // NOOP manager, context will always be root
    try (ContextScope scope = context.attach()) {
      assertSame(root(), scope.context());
      assertSame(root(), Context.current());
      ContextContinuation cont = context.capture();
      assertSame(root(), cont.context());
      cont.release();
    }

    // NOOP manager, context will always be root
    assertTrue(manager.isCaptureEnabled());
    manager.setCaptureEnabled(false); // custom manager ignores the capture flag
    assertTrue(manager.isCaptureEnabled());
    assertSame(context, context.checkpoint());

    swapped = context.swap();
    assertSame(root(), swapped);
    assertSame(root(), Context.current());
    assertSame(root(), swapped.swap());
    assertSame(root(), Context.current());

    // NOOP manager, no events emitted
    listener.assertNoEvents();
  }
}
