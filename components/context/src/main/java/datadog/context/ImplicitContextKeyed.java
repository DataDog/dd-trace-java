package datadog.context;

import javax.annotation.ParametersAreNonnullByDefault;

/** {@link Context} value that has its own implicit {@link ContextKey}. */
@ParametersAreNonnullByDefault
public interface ImplicitContextKeyed {
  /**
   * Creates a new context with this value under its chosen key.
   *
   * @param context the context to copy the original values from.
   * @return the new context with the implicitly keyed value.
   * @see Context#with(ImplicitContextKeyed)
   */
  Context storeInto(Context context);
}
