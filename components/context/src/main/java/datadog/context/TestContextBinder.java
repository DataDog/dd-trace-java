package datadog.context;

/** Test class that always delegates to the latest registered {@link ContextBinder}. */
final class TestContextBinder implements ContextBinder {
  private static final ContextBinder TEST_INSTANCE = new TestContextBinder();

  private TestContextBinder() {}

  static boolean register() {
    // attempt to register before binder choice is locked, then check if we succeeded
    ContextProviders.customBinder = TEST_INSTANCE;
    return ContextProviders.binder() == TEST_INSTANCE;
  }

  @Override
  public Context from(Object carrier) {
    return delegate().from(carrier);
  }

  @Override
  public void attachTo(Object carrier, Context context) {
    delegate().attachTo(carrier, context);
  }

  @Override
  public Context detachFrom(Object carrier) {
    return delegate().detachFrom(carrier);
  }

  private static ContextBinder delegate() {
    ContextBinder delegate = ContextProviders.customBinder;
    if (delegate == TEST_INSTANCE) {
      // fall back to default context binder
      return WeakMapContextBinder.INSTANCE;
    } else {
      return delegate;
    }
  }
}
