package datadog.trace.bootstrap;

import datadog.trace.api.InstrumenterConfig;

/** Allocates {@link ContextStore}s. */
public final class ContextStores {

  // fast lookup available for fixed number of stores
  public static final int FAST_STORE_ID_LIMIT = 32;

  private static final boolean MAP_PER_STORE =
      InstrumenterConfig.get().isRuntimeContextMapPerStore();

  public static final String STORES_DESCRIPTOR =
      MAP_PER_STORE
          ? "Ldatadog/trace/bootstrap/FieldBackedContextStores;"
          : "Ldatadog/trace/bootstrap/BoxedContextStores;";

  public static final String STORE_DESCRIPTOR =
      MAP_PER_STORE
          ? "Ldatadog/trace/bootstrap/FieldBackedContextStore;"
          : "Ldatadog/trace/bootstrap/BoxedContextStore;";

  public static int getContextStoreId(String keyClassName, String contextClassName) {
    if (MAP_PER_STORE) {
      return FieldBackedContextStores.getContextStoreId(keyClassName, contextClassName);
    } else {
      return BoxedContextStores.getContextStoreId(keyClassName, contextClassName);
    }
  }

  public static ContextStore<?, ?> getContextStore(final int storeId) {
    if (MAP_PER_STORE) {
      return FieldBackedContextStores.getContextStore(storeId);
    } else {
      return BoxedContextStores.getContextStore(storeId);
    }
  }
}
