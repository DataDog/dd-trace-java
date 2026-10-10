package datadog.trace.api.openfeature;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers the last exposure sent for each (flag, subject). An exposure is sent again only when
 * its allocation, variant or serial id changes. Thread-safe.
 */
final class ExposureDeduplicationCache {

  // Matches the agent's exposure writer, which still deduplicates on its own, so both caches evict
  // at the same size.
  static final int CAPACITY = 1 << 16;

  static final ExposureDeduplicationCache INSTANCE = new ExposureDeduplicationCache(CAPACITY);

  private final Map<Key, Sent> entries;

  ExposureDeduplicationCache(final int capacity) {
    this.entries = new BoundedLru(capacity);
  }

  synchronized boolean contains(
      final String flag,
      final String subject,
      final String allocation,
      final String variant,
      final Integer serialId) {
    return new Sent(allocation, variant, serialId).equals(entries.get(new Key(flag, subject)));
  }

  synchronized void record(
      final String flag,
      final String subject,
      final String allocation,
      final String variant,
      final Integer serialId) {
    entries.put(new Key(flag, subject), new Sent(allocation, variant, serialId));
  }

  synchronized int size() {
    return entries.size();
  }

  synchronized void clear() {
    entries.clear();
  }

  private static final class Key {
    private final String flag;
    private final String subject;

    Key(final String flag, final String subject) {
      this.flag = flag;
      this.subject = subject;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof Key)) {
        return false;
      }
      final Key key = (Key) o;
      return Objects.equals(flag, key.flag) && Objects.equals(subject, key.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(flag, subject);
    }
  }

  private static final class Sent {
    private final String allocation;
    private final String variant;
    private final Integer serialId;

    Sent(final String allocation, final String variant, final Integer serialId) {
      this.allocation = allocation;
      this.variant = variant;
      this.serialId = serialId;
    }

    @Override
    public boolean equals(final Object o) {
      if (!(o instanceof Sent)) {
        return false;
      }
      final Sent entry = (Sent) o;
      return Objects.equals(allocation, entry.allocation)
          && Objects.equals(variant, entry.variant)
          && Objects.equals(serialId, entry.serialId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(allocation, variant, serialId);
    }
  }

  private static final class BoundedLru extends LinkedHashMap<Key, Sent> {
    private final int capacity;

    BoundedLru(final int capacity) {
      super(16, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(final Map.Entry<Key, Sent> eldest) {
      return size() > capacity;
    }
  }
}
