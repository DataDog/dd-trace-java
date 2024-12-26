package datadog.context;

/** {@link Context} value that has its own implicit {@link ContextKey}. */
public interface ImplicitContextKeyed {

  /**
   * Creates a new context with this value under its chosen key.
   *
   * @return New context with the implicitly keyed value.
   */
  Context storeInto(Context context);
}
