package datadog.context;

/** Provides {@link ContextManager} and {@link ContextBinder} implementations. */
final class ContextProviders {

  static volatile ContextManager customManager;
  static volatile ContextBinder customBinder;

  static ContextManager manager() {
    return ContextManager.Provided.INSTANCE; // may be overridden by instrumentation
  }

  static ContextBinder binder() {
    return ContextBinder.Provided.INSTANCE; // may be overridden by instrumentation
  }
}
