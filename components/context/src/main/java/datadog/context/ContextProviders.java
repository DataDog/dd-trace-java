package datadog.context;

/** Provides {@link ContextManager} and {@link ContextBinder} implementations. */
final class ContextProviders {

  static volatile ContextManager customManager;
  static volatile ContextBinder customBinder;

  private static final class ProvidedManager {
    static final ContextManager INSTANCE =
        null != ContextProviders.customManager
            ? ContextProviders.customManager
            : ThreadLocalContextManager.INSTANCE;
  }

  private static final class ProvidedBinder {
    static final ContextBinder INSTANCE =
        null != ContextProviders.customBinder
            ? ContextProviders.customBinder
            : WeakMapContextBinder.INSTANCE;
  }

  static ContextManager manager() {
    return ProvidedManager.INSTANCE; // may be overridden by instrumentation
  }

  static ContextBinder binder() {
    return ProvidedBinder.INSTANCE; // may be overridden by instrumentation
  }
}
