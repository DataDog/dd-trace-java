package datadog.trace.bootstrap;

/** Instrumentation Context API */
public class InstrumentationContext {
  private InstrumentationContext() {}

  /**
   * Find a {@link ContextStore} instance for given key class and context class.
   *
   * <p>Conceptually this can be thought of as a map lookup to fetch a second level map given
   * keyClass.
   *
   * <p>However, the implementation is actually provided by bytecode transformation for performance
   * reasons.
   *
   * <p>This method must only be called within an Advice class.
   *
   * @param keyClass The key class context is attached to.
   * @param contextClass The context class attached to the user class.
   * @param <K> key class
   * @param <C> context class
   * @return The instance of context store for given arguments.
   */
  public static <K, C> ContextStore<K, C> get(
      final Class<K> keyClass, final Class<C> contextClass) {
    throw new RuntimeException(
        "Calls to this method will be rewritten by Instrumentation Context Provider (e.g. FieldBackedProvider)."
            + " If you get this exception, this method has not been rewritten."
            + " Ensure instrumentation class has a contextStore method and the call to InstrumentationContext.get happens directly in an instrumentation Advice class."
            + " See how_instrumentations_work.md for details.");
  }

  /**
   * Find a {@link ContextStore} instance for given key class name and context class name.
   *
   * @apiNote This is a fallback for when the key/context are only available as string constants at
   *     compile time, in all other situations prefer the method that accepts class literals
   */
  public static <K, C> ContextStore<K, C> get(final String keyClass, final String contextClass) {
    throw new RuntimeException(
        "Calls to this method will be rewritten by Instrumentation Context Provider (e.g. FieldBackedProvider)."
            + " If you get this exception, this method has not been rewritten."
            + " Ensure instrumentation class has a contextStore method and the call to InstrumentationContext.get happens directly in an instrumentation Advice class."
            + " See how_instrumentations_work.md for details.");
  }
}
