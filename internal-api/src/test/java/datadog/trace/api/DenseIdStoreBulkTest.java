package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Prototype: reserveAbsent / appendAt, the dense store's bulk path for fused tag runs. */
class DenseIdStoreBulkTest {
  static final long[] RUN = {KnownTags.DB_USER, KnownTags.DB_INSTANCE, KnownTags.PEER_HOSTNAME};

  static long maskOf(long... ids) {
    long mask = 0L;
    for (long id : ids) {
      mask |= DenseIdStore.slotBit(id);
    }
    return mask;
  }

  @Test
  void aRunOfDistinctSlotsIsTheTestPremise() {
    assertEquals(RUN.length, Long.bitCount(maskOf(RUN)), "the run's tags must have distinct slots");
    for (long id : RUN) {
      assertEquals(DenseIdStore.levelOf(RUN[0]), DenseIdStore.levelOf(id));
    }
  }

  @Test
  void reservesAndAppendsAnAbsentRun() {
    DenseIdStore store = new DenseIdStore(null);
    int i = store.reserveAbsent(maskOf(RUN), DenseIdStore.levelOf(RUN[0]), RUN.length);
    assertEquals(0, i);
    store.appendAt(i, RUN[0], "app");
    store.appendAt(i + 1, RUN[1], "petclinic");
    store.appendAt(i + 2, RUN[2], "db.internal");

    assertEquals(3, store.size());
    assertEquals("app", store.load(KnownTags.DB_USER));
    assertEquals("petclinic", store.load(KnownTags.DB_INSTANCE));
    assertEquals("db.internal", store.load(KnownTags.PEER_HOSTNAME));
  }

  @Test
  void appendsAfterExistingTags() {
    DenseIdStore store = new DenseIdStore(null);
    store.store(KnownTags.HTTP_ROUTE, "/r");
    assertTrue((maskOf(RUN) & DenseIdStore.slotBit(KnownTags.HTTP_ROUTE)) == 0L, "premise");

    int i = store.reserveAbsent(maskOf(RUN), DenseIdStore.levelOf(RUN[0]), RUN.length);
    assertEquals(1, i);
    store.appendAt(i, RUN[0], "app");
    store.appendAt(i + 1, RUN[1], "petclinic");
    store.appendAt(i + 2, RUN[2], "db.internal");

    assertEquals(4, store.size());
    assertEquals("/r", store.load(KnownTags.HTTP_ROUTE));
    assertEquals("db.internal", store.load(KnownTags.PEER_HOSTNAME));
    // a later single store of a run tag finds it and overwrites in place
    store.store(KnownTags.DB_USER, "other");
    assertEquals(4, store.size());
    assertEquals("other", store.load(KnownTags.DB_USER));
  }

  @Test
  void declinesWhenATagOfTheRunIsPresent() {
    DenseIdStore store = new DenseIdStore(null);
    store.store(KnownTags.DB_INSTANCE, "already");

    assertEquals(-1, store.reserveAbsent(maskOf(RUN), DenseIdStore.levelOf(RUN[0]), RUN.length));
    assertEquals(1, store.size());
  }

  @Test
  void declinesWhenATagOfTheRunIsTombstoned() {
    DenseIdStore parent = new DenseIdStore(null);
    parent.store(KnownTags.DB_USER, "inherited");
    DenseIdStore store = new DenseIdStore(parent);
    store.remove(KnownTags.DB_USER);

    assertEquals(-1, store.reserveAbsent(maskOf(RUN), DenseIdStore.levelOf(RUN[0]), RUN.length));
  }

  @Test
  void theHashStoreHasNoBulkPath() {
    assertEquals(-1, new HashIdStore(null).reserveAbsent(maskOf(RUN), 1, RUN.length));
  }
}
