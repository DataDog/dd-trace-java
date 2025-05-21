package datadog.context;

/** Test class that always delegates to the latest registered {@link ContextManager}. */
final class TestContextManager implements ContextManager {
  private static final ContextManager TEST_INSTANCE = new TestContextManager();

  private TestContextManager() {}

  static boolean register() {
    // attempt to register before manager choice is locked, then check if we succeeded
    ContextProviders.customManager = TEST_INSTANCE;
    return ContextProviders.manager() == TEST_INSTANCE;
  }

  @Override
  public Context current() {
    return delegate().current();
  }

  @Override
  public ContextScope attach(Context context) {
    return delegate().attach(context);
  }

  @Override
  public Context swap(Context context) {
    return delegate().swap(context);
  }

  private static ContextManager delegate() {
    ContextManager delegate = ContextProviders.customManager;
    if (delegate == TEST_INSTANCE) {
      // fall back to default context manager
      return ThreadLocalContextManager.INSTANCE;
    } else {
      return delegate;
    }
  }
}
