package datadog.trace.bootstrap.instrumentation.api;

/** A {@link ScopedContext} value that defines its own implicit {@link ScopedContextKey}. */
public interface ImplicitContextKeyed {

  /** Creates a new context based on the current context with this value. */
  ScopedContext storeInto(ScopedContext context);
}
