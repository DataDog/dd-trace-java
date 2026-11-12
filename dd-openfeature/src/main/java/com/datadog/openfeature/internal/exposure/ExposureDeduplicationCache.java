package com.datadog.openfeature.internal.exposure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers the last exposure recorded for each (flag, subject). An exposure is sent again only
 * when its allocation, variant or serial id changes. Thread-safe.
 */
public final class ExposureDeduplicationCache {
  static final int CAPACITY = 1 << 16;

  private final Map<Key, Sent> entries;

  /** Creates a cache remembering up to 65536 subjects, evicting the least recently used ones. */
  public ExposureDeduplicationCache() {
    this(CAPACITY);
  }

  ExposureDeduplicationCache(final int capacity) {
    this.entries = new BoundedLru(capacity);
  }

  /**
   * Tells whether an exposure was already recorded for this subject.
   *
   * @param flag the flag key.
   * @param subject the subject, the targeting key.
   * @param allocation the allocation key.
   * @param variant the variant key.
   * @param serialId the split serial id, or {@code null} if absent.
   * @return whether the last exposure recorded for this flag and subject is the same one.
   */
  public synchronized boolean contains(
      final String flag,
      final String subject,
      final String allocation,
      final String variant,
      final Integer serialId) {
    return new Sent(allocation, variant, serialId).equals(this.entries.get(new Key(flag, subject)));
  }

  /**
   * Records an exposure as the last one of this subject.
   *
   * @param flag the flag key.
   * @param subject the subject, the targeting key.
   * @param allocation the allocation key.
   * @param variant the variant key.
   * @param serialId the split serial id, or {@code null} if absent.
   * @return whether the exposure is new or changed for this flag and subject.
   */
  public synchronized boolean recordIfChanged(
      final String flag,
      final String subject,
      final String allocation,
      final String variant,
      final Integer serialId) {
    final Sent sent = new Sent(allocation, variant, serialId);
    return !sent.equals(this.entries.put(new Key(flag, subject), sent));
  }

  /**
   * Gets the number of subjects remembered.
   *
   * @return the number of (flag, subject) entries.
   */
  public synchronized int size() {
    return this.entries.size();
  }

  /** Forgets every recorded exposure. */
  public synchronized void clear() {
    this.entries.clear();
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
      return Objects.equals(this.flag, key.flag) && Objects.equals(this.subject, key.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.flag, this.subject);
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
      return Objects.equals(this.allocation, entry.allocation)
          && Objects.equals(this.variant, entry.variant)
          && Objects.equals(this.serialId, entry.serialId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.allocation, this.variant, this.serialId);
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
      return size() > this.capacity;
    }
  }
}
