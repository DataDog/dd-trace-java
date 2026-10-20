package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Point reads of a frozen dense store that several threads share -- a span prototype's tags, the
 * tracer-level tags -- must not share state: each read builds its own reader or Entry.
 */
class DenseIdStoreConcurrentReadTest {

  @Test
  void concurrentPointReadsOfASharedStoreKeepTheirOwnValues() throws Exception {
    List<Long> ids = new ArrayList<>();
    for (java.lang.reflect.Field field : KnownTags.class.getFields()) {
      if (field.getType() == long.class && ids.size() < 12) {
        long id = field.getLong(null);
        if (KnownTagCodec.isKnown(id) && !ids.contains(id)) {
          ids.add(id);
        }
      }
    }
    DenseIdStore store = new DenseIdStore(null);
    for (long id : ids) {
      store.set(id, KnownTagCodec.nameOf(id)); // each value names its own tag
    }
    TagMap shared = TagMap.viewOf(store, null, false);

    int threads = 8;
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<String> mismatch = new AtomicReference<>();
    List<Thread> readers = new ArrayList<>();
    for (int t = 0; t < threads; ++t) {
      final int offset = t;
      Thread reader =
          new Thread(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  return;
                }
                for (int i = 0; i < 20_000 && mismatch.get() == null; ++i) {
                  long id = ids.get((i + offset) % ids.size());
                  String name = KnownTagCodec.nameOf(id);
                  TagMap.Entry entry = shared.getEntry(id);
                  TagMap.EntryReader viaReader = store.getEntryReader(id);
                  if (!name.equals(entry.tag()) || !name.equals(entry.objectValue())) {
                    mismatch.set(name + " read as " + entry.tag() + "=" + entry.objectValue());
                  } else if (!name.equals(viaReader.tag())
                      || !name.equals(viaReader.objectValue())) {
                    mismatch.set(
                        name + " read as " + viaReader.tag() + "=" + viaReader.objectValue());
                  }
                }
              });
      readers.add(reader);
      reader.start();
    }
    start.countDown();
    for (Thread reader : readers) {
      reader.join(30_000);
      assertTrue(!reader.isAlive(), "reader finished");
    }
    assertEquals(null, mismatch.get());
  }
}
