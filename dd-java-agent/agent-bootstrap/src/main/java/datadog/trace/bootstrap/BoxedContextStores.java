package datadog.trace.bootstrap;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Allocates {@link ContextStore} ids and keeps track of allocated stores. */
public final class BoxedContextStores {

  private static final Logger log = LoggerFactory.getLogger(BoxedContextStores.class);

  // these fields will be accessed directly from field-injected instrumentation
  public static final BoxedContextStore contextStore0 = new BoxedContextStore(0);
  public static final BoxedContextStore contextStore1 = new BoxedContextStore(1);
  public static final BoxedContextStore contextStore2 = new BoxedContextStore(2);
  public static final BoxedContextStore contextStore3 = new BoxedContextStore(3);
  public static final BoxedContextStore contextStore4 = new BoxedContextStore(4);
  public static final BoxedContextStore contextStore5 = new BoxedContextStore(5);
  public static final BoxedContextStore contextStore6 = new BoxedContextStore(6);
  public static final BoxedContextStore contextStore7 = new BoxedContextStore(7);
  public static final BoxedContextStore contextStore8 = new BoxedContextStore(8);
  public static final BoxedContextStore contextStore9 = new BoxedContextStore(9);
  public static final BoxedContextStore contextStore10 = new BoxedContextStore(10);
  public static final BoxedContextStore contextStore11 = new BoxedContextStore(11);
  public static final BoxedContextStore contextStore12 = new BoxedContextStore(12);
  public static final BoxedContextStore contextStore13 = new BoxedContextStore(13);
  public static final BoxedContextStore contextStore14 = new BoxedContextStore(14);
  public static final BoxedContextStore contextStore15 = new BoxedContextStore(15);
  public static final BoxedContextStore contextStore16 = new BoxedContextStore(16);
  public static final BoxedContextStore contextStore17 = new BoxedContextStore(17);
  public static final BoxedContextStore contextStore18 = new BoxedContextStore(18);
  public static final BoxedContextStore contextStore19 = new BoxedContextStore(19);
  public static final BoxedContextStore contextStore20 = new BoxedContextStore(20);
  public static final BoxedContextStore contextStore21 = new BoxedContextStore(21);
  public static final BoxedContextStore contextStore22 = new BoxedContextStore(22);
  public static final BoxedContextStore contextStore23 = new BoxedContextStore(23);
  public static final BoxedContextStore contextStore24 = new BoxedContextStore(24);
  public static final BoxedContextStore contextStore25 = new BoxedContextStore(25);
  public static final BoxedContextStore contextStore26 = new BoxedContextStore(26);
  public static final BoxedContextStore contextStore27 = new BoxedContextStore(27);
  public static final BoxedContextStore contextStore28 = new BoxedContextStore(28);
  public static final BoxedContextStore contextStore29 = new BoxedContextStore(29);
  public static final BoxedContextStore contextStore30 = new BoxedContextStore(30);
  public static final BoxedContextStore contextStore31 = new BoxedContextStore(31);

  // keep track of all allocated stores so far
  private static volatile BoxedContextStore[] stores = {
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

  private static final Map<String, BoxedContextStore> storesByName = new ConcurrentHashMap<>();
  private static final Object allocationLock = new Object();

  private static int nextStoreId;

  public static int getContextStoreId(String keyClassName, String contextClassName) {
    String storeName = keyClassName + ';' + contextClassName;
    BoxedContextStore store = storesByName.get(storeName);
    if (store == null) {
      BoxedContextStore existing;
      synchronized (allocationLock) {
        existing = storesByName.putIfAbsent(storeName, store = allocateStore(nextStoreId++));
        if (existing != null) {
          nextStoreId--;
          return existing.storeId;
        }
      }
      log.debug(
          "Allocated ContextStore #{} - instrumentation.target.context={}->{}",
          store.storeId,
          keyClassName,
          contextClassName);
    }
    return store.storeId;
  }

  public static BoxedContextStore getContextStore(final int storeId) {
    return stores[storeId];
  }

  private static BoxedContextStore allocateStore(int allocatedId) {
    BoxedContextStore[] snapshot = stores;
    if (allocatedId >= snapshot.length) {
      stores = snapshot = Arrays.copyOf(snapshot, Math.max(allocatedId + 1, snapshot.length + 16));
    }
    BoxedContextStore allocatedStore = snapshot[allocatedId];
    if (allocatedStore == null) {
      snapshot[allocatedId] = allocatedStore = new BoxedContextStore(allocatedId);
    }
    return allocatedStore;
  }
}
