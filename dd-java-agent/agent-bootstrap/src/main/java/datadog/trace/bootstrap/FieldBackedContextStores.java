package datadog.trace.bootstrap;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Allocates {@link ContextStore} ids and keeps track of allocated stores. */
public final class FieldBackedContextStores {

  private static final Logger log = LoggerFactory.getLogger(FieldBackedContextStores.class);

  // provide fast lookup for a fixed number of stores
  public static final int FAST_STORE_ID_LIMIT = 32;

  // these fields will be accessed directly from field-injected instrumentation
  public static final FieldBackedContextStore contextStore0 = new FieldBackedContextStore(0);
  public static final FieldBackedContextStore contextStore1 = new FieldBackedContextStore(1);
  public static final FieldBackedContextStore contextStore2 = new FieldBackedContextStore(2);
  public static final FieldBackedContextStore contextStore3 = new FieldBackedContextStore(3);
  public static final FieldBackedContextStore contextStore4 = new FieldBackedContextStore(4);
  public static final FieldBackedContextStore contextStore5 = new FieldBackedContextStore(5);
  public static final FieldBackedContextStore contextStore6 = new FieldBackedContextStore(6);
  public static final FieldBackedContextStore contextStore7 = new FieldBackedContextStore(7);
  public static final FieldBackedContextStore contextStore8 = new FieldBackedContextStore(8);
  public static final FieldBackedContextStore contextStore9 = new FieldBackedContextStore(9);
  public static final FieldBackedContextStore contextStore10 = new FieldBackedContextStore(10);
  public static final FieldBackedContextStore contextStore11 = new FieldBackedContextStore(11);
  public static final FieldBackedContextStore contextStore12 = new FieldBackedContextStore(12);
  public static final FieldBackedContextStore contextStore13 = new FieldBackedContextStore(13);
  public static final FieldBackedContextStore contextStore14 = new FieldBackedContextStore(14);
  public static final FieldBackedContextStore contextStore15 = new FieldBackedContextStore(15);
  public static final FieldBackedContextStore contextStore16 = new FieldBackedContextStore(16);
  public static final FieldBackedContextStore contextStore17 = new FieldBackedContextStore(17);
  public static final FieldBackedContextStore contextStore18 = new FieldBackedContextStore(18);
  public static final FieldBackedContextStore contextStore19 = new FieldBackedContextStore(19);
  public static final FieldBackedContextStore contextStore20 = new FieldBackedContextStore(20);
  public static final FieldBackedContextStore contextStore21 = new FieldBackedContextStore(21);
  public static final FieldBackedContextStore contextStore22 = new FieldBackedContextStore(22);
  public static final FieldBackedContextStore contextStore23 = new FieldBackedContextStore(23);
  public static final FieldBackedContextStore contextStore24 = new FieldBackedContextStore(24);
  public static final FieldBackedContextStore contextStore25 = new FieldBackedContextStore(25);
  public static final FieldBackedContextStore contextStore26 = new FieldBackedContextStore(26);
  public static final FieldBackedContextStore contextStore27 = new FieldBackedContextStore(27);
  public static final FieldBackedContextStore contextStore28 = new FieldBackedContextStore(28);
  public static final FieldBackedContextStore contextStore29 = new FieldBackedContextStore(29);
  public static final FieldBackedContextStore contextStore30 = new FieldBackedContextStore(30);
  public static final FieldBackedContextStore contextStore31 = new FieldBackedContextStore(31);

  // keep track of all allocated stores so far
  private static volatile FieldBackedContextStore[] stores = {
    contextStore0,
    contextStore1,
    contextStore2,
    contextStore3,
    contextStore4,
    contextStore5,
    contextStore6,
    contextStore7,
    contextStore8,
    contextStore9,
    contextStore10,
    contextStore11,
    contextStore12,
    contextStore13,
    contextStore14,
    contextStore15,
    contextStore16,
    contextStore17,
    contextStore18,
    contextStore19,
    contextStore20,
    contextStore21,
    contextStore22,
    contextStore23,
    contextStore24,
    contextStore25,
    contextStore26,
    contextStore27,
    contextStore28,
    contextStore29,
    contextStore30,
    contextStore31
  };

  public static FieldBackedContextStore getContextStore(final int storeId) {
    return stores[storeId]; // createStore ensures array is big enough for allocated storeIds
  }

  private static final ConcurrentHashMap<String, FieldBackedContextStore> STORES_BY_NAME =
      new ConcurrentHashMap<>();

  @SuppressFBWarnings("JLM_JSR166_UTILCONCURRENT_MONITORENTER")
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
              "Allocated ContextStore #{} - instrumentation.target.context={}->{}",
              newStoreId,
              keyClassName,
              contextClassName);
          return newStoreId;
        }
      }
    }
    return existingStore.storeId;
  }

  private static String storeName(final String keyClassName, final String contextClassName) {
    return keyClassName + ';' + contextClassName;
  }

  // this method should only be called while holding a synchronized lock on STORES_BY_NAME
  private static FieldBackedContextStore createStore(final int storeId) {
    if (storeId < FAST_STORE_ID_LIMIT) {
      return stores[storeId]; // pre-allocated
    }
    if (stores.length <= storeId) {
      stores = Arrays.copyOf(stores, storeId + 16);
    }
    // check in case an earlier thread created the store but didn't end up using it
    FieldBackedContextStore store = stores[storeId];
    if (null == store) {
      store = new FieldBackedContextStore(storeId);
      stores[storeId] = store;
    }
    return store;
  }
}
