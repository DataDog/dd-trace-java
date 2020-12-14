package datadog.trace.bootstrap;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FieldBackedContextStores {

  // provide fast lookup for a small number of stores
  public static final int FAST_STORE_ID_LIMIT = 8;

  public static final FieldBackedContextStore contextStore0 = new FieldBackedContextStore(0);
  public static final FieldBackedContextStore contextStore1 = new FieldBackedContextStore(1);
  public static final FieldBackedContextStore contextStore2 = new FieldBackedContextStore(2);
  public static final FieldBackedContextStore contextStore3 = new FieldBackedContextStore(3);
  public static final FieldBackedContextStore contextStore4 = new FieldBackedContextStore(4);
  public static final FieldBackedContextStore contextStore5 = new FieldBackedContextStore(5);
  public static final FieldBackedContextStore contextStore6 = new FieldBackedContextStore(6);
  public static final FieldBackedContextStore contextStore7 = new FieldBackedContextStore(7);

  // fall-back to slightly slower lookup for any additional stores
  private static volatile FieldBackedContextStore[] extraStores = new FieldBackedContextStore[8];

  public static FieldBackedContextStore getContextStore(final int storeId) {
    switch (storeId) {
      case 0:
        return contextStore0;
      case 1:
        return contextStore1;
      case 2:
        return contextStore2;
      case 3:
        return contextStore3;
      case 4:
        return contextStore4;
      case 5:
        return contextStore5;
      case 6:
        return contextStore6;
      case 7:
        return contextStore7;
      default:
        return extraStores[storeId - FAST_STORE_ID_LIMIT];
    }
  }

  private static final ConcurrentHashMap<String, FieldBackedContextStore> STORES_BY_NAME =
      new ConcurrentHashMap<>();

  public static int getContextStoreId(final String keyClassName, final String contextClassName) {
    final String storeName = storeName(keyClassName, contextClassName);
    FieldBackedContextStore existingStore = STORES_BY_NAME.get(storeName);
    if (null == existingStore) {
      synchronized (STORES_BY_NAME) {
        // speculatively create the next store in the sequence and attempt to map this name to it;
        // if another thread has mapped this name then the store will be kept for the next mapping
        final int newStoreId = STORES_BY_NAME.size();
        existingStore = STORES_BY_NAME.putIfAbsent(storeName, createStore(newStoreId));
        if (null == existingStore) {
          log.debug(
              "Allocated ContextStore #{} to {} -> {}", newStoreId, keyClassName, contextClassName);
          return newStoreId;
        }
      }
    }
    return existingStore.storeId;
  }

  private static String storeName(final String keyClassName, final String contextClassName) {
    return keyClassName + ';' + contextClassName;
  }

  private static FieldBackedContextStore createStore(final int storeId) {
    if (storeId < FAST_STORE_ID_LIMIT) {
      return getContextStore(storeId);
    }
    final int arrayIndex = storeId - FAST_STORE_ID_LIMIT;
    if (extraStores.length <= arrayIndex) {
      extraStores = Arrays.copyOf(extraStores, extraStores.length << 1);
    }
    // check in case an earlier thread created the store but didn't end up using it
    FieldBackedContextStore store = extraStores[arrayIndex];
    if (null == store) {
      store = new FieldBackedContextStore(storeId);
      extraStores[arrayIndex] = store;
    }
    return store;
  }
}
