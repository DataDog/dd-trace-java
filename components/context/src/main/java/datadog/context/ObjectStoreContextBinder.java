package datadog.context;

import static datadog.context.Context.root;
import static java.util.Objects.requireNonNull;

import datadog.instrument.fieldinject.ObjectStore;

/** {@link ContextBinder} that uses {@link ObjectStore} to track context carriers. */
final class ObjectStoreContextBinder implements ContextBinder {
  static final ContextBinder INSTANCE = new ObjectStoreContextBinder();

  private static final ObjectStore<Object, Context> CONTEXT_STORE =
      ObjectStore.of(Object.class, Context.class);

  @Override
  public Context from(Object carrier) {
    requireNonNull(carrier, "Context carrier cannot be null");
    Context bound = CONTEXT_STORE.get(carrier);
    return null != bound ? bound : root();
  }

  @Override
  public void attachTo(Object carrier, Context context) {
    requireNonNull(carrier, "Context carrier cannot be null");
    requireNonNull(context, "Context cannot be null. Use detachFrom() instead.");
    CONTEXT_STORE.put(carrier, context);
  }

  @Override
  public Context detachFrom(Object carrier) {
    requireNonNull(carrier, "Context key cannot be null");
    Context previous = CONTEXT_STORE.remove(carrier);
    return null != previous ? previous : root();
  }
}
