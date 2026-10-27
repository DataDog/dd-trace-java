package datadog.trace.bootstrap;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Allocates {@link ContextStore} ids and keeps track of allocated stores. */
public final class FieldBackedContextStores {

  private static final Logger log = LoggerFactory.getLogger(FieldBackedContextStores.class);

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

  private static final ConcurrentHashMap<String, FieldBackedContextStore> storesByName =
      new ConcurrentHashMap<>();
  private static final Object allocationLock = new Object();

  private static int nextStoreId;

  public static int getContextStoreId(String keyClassName, String contextClassName) {
    String storeName = keyClassName + ';' + contextClassName;
    FieldBackedContextStore store = storesByName.get(storeName);
    if (store == null) {
      synchronized (allocationLock) {
        // speculatively create the next store in the sequence and attempt to map this name to it;
        // if another thread has mapped this name then the store will be kept for the next mapping
        FieldBackedContextStore existing =
            storesByName.putIfAbsent(storeName, store = allocateStore(nextStoreId));
        if (existing != null) {
          return existing.storeId;
        }
        nextStoreId++;
      }
      log.debug(
          "Allocated ContextStore #{} - instrumentation.target.context={}->{}",
          store.storeId,
          keyClassName,
          contextClassName);
    }
    return store.storeId;
  }

  public static FieldBackedContextStore getContextStore(final int storeId) {
    return stores[storeId]; // guaranteed to be populated by getContextStoreId
  }

  // this method should only be called while holding the allocation lock
  private static FieldBackedContextStore allocateStore(int storeId) {
    FieldBackedContextStore[] snapshot = stores;
    if (storeId >= snapshot.length) {
      stores = snapshot = Arrays.copyOf(snapshot, storeId + 16);
    }
    // check for pre-allocated / speculatively allocated stores
    FieldBackedContextStore store = snapshot[storeId];
    if (store == null) {
      snapshot[storeId] = store = new FieldBackedContextStore(storeId);
    }
    return store;
  }
}
