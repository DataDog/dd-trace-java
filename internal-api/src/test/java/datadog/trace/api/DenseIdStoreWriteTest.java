package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DenseIdStoreWriteTest {

  @Test
  void getAndPutReadsTheValueOnce() {
    DenseIdStore store = new DenseIdStore(null);
    TagMap.EntryReader reader = readableOnce(TagMap.Entry.create(KnownTags.HTTP_METHOD, "GET"));

    assertNull(store.getAndPut(reader));
    assertEquals(1, store.size());
    assertEquals("GET", store.getEntryReader(KnownTags.HTTP_METHOD).objectValue());
  }

  @Test
  void aReaderThatFailsLeavesNoSlotBehind() {
    DenseIdStore store = new DenseIdStore(null);
    TagMap.EntryReader failing = failingReader(TagMap.Entry.create(KnownTags.HTTP_METHOD, "GET"));

    assertThrows(IllegalStateException.class, () -> store.getAndPut(failing));
    assertEquals(0, store.size());
    assertNull(store.getEntryReader(KnownTags.HTTP_METHOD));
  }

  /** {@code entry}, except that a second {@code objectValue()} call throws. */
  private static TagMap.EntryReader readableOnce(TagMap.EntryReader entry) {
    AtomicInteger reads = new AtomicInteger();
    return proxy(entry, () -> reads.incrementAndGet() > 1);
  }

  /** {@code entry}, except that every {@code objectValue()} call throws. */
  private static TagMap.EntryReader failingReader(TagMap.EntryReader entry) {
    return proxy(entry, () -> true);
  }

  private interface Fails {
    boolean now();
  }

  private static TagMap.EntryReader proxy(TagMap.EntryReader entry, Fails fails) {
    return (TagMap.EntryReader)
        Proxy.newProxyInstance(
            TagMap.EntryReader.class.getClassLoader(),
            new Class<?>[] {TagMap.EntryReader.class},
            (self, method, args) -> {
              if (method.getName().equals("objectValue") && fails.now()) {
                throw new IllegalStateException("objectValue read again");
              }
              try {
                return method.invoke(entry, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
