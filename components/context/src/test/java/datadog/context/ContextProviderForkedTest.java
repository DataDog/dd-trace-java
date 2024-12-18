package datadog.context;

import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ContextProviderForkedTest {

  @Test
  void testCustomBinder() {
    // register a NOOP context binder
    ContextBinder.register(new ContextBinder() {
      @Override
      public Context from(Object carrier) {
        return root();
      }

      @Override
      public void attachTo(Object carrier, Context context) {
        // no-op
      }

      @Override
      public Context detachFrom(Object carrier) {
        return root();
      }
    });

    Context context = root().with(STRING_KEY, "value");

    // NOOP binder, context will always be root
    Object carrier = new Object();
    context.attachTo(carrier);
    assertEquals(root(), Context.from(carrier));
  }

  @Test
  void testCustomManager() {
    // register a NOOP context manager
    ContextManager.register(new ContextManager() {
      @Override
      public Context root() {
        return EmptyContext.INSTANCE;
      }

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

      @Override
      public Context detach() {
        return root();
      }
    });

    Context context = root().with(STRING_KEY, "value");

    // NOOP manager, context will always be root
    try (ContextScope ignored = context.attach()) {
      assertEquals(root(), Context.current());
    }
  }
}
