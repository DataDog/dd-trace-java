package datadog.trace.bootstrap;

/**
 * Accessor API for {@link ContextStore} keys that store their context in a bytecode-injected field.
 */
public interface FieldBackedContextAccessor {
  /** Retrieves context from the field backing the given store. */
  Object get$__datadogContext$(int storeId);

  /** Stores context in the field backing the given store. */
  void put$__datadogContext$(int storeId, Object context);
}
