package datadog.context;

/**
 * Provides {@link ContextManager} and {@link ContextBinder} implementations.
 */
final class ContextProviders {
  static volatile ContextManager customManager;
  static volatile ContextBinder customBinder;

  private static final class ProvidedManager {
    static final ContextManager INSTANCE = null != ContextProviders.customManager
        ? ContextProviders.customManager
        : ThreadLocalContextManager.INSTANCE;
  }

  private static final class ProvidedBinder {
    static final ContextBinder INSTANCE = null != ContextProviders.customBinder
        ? ContextProviders.customBinder
        : WeakMapContextBinder.INSTANCE;
  }

  static ContextManager manager() {
    // may be overridden by instrumentation
    return ProvidedManager.INSTANCE;
  }

  static ContextBinder binder() {
    // may be overridden by instrumentation
    return ProvidedBinder.INSTANCE;
  }
}
