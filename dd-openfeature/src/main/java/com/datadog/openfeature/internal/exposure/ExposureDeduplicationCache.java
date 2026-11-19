package com.datadog.openfeature.internal.exposure;

import datadog.trace.util.Hashtable;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Remembers the last exposure recorded for each (flag, subject). An exposure is sent again only
 * when its allocation, variant or serial id changes. Thread-safe.
 *
 * <p>Each subject is one table entry, keyed by both parts without a key object and updated in
 * place, and linked into the least-recently-used order, so a lookup allocates nothing and a full
 * cache holds one object per subject.
 */
public final class ExposureDeduplicationCache {
  static final int CAPACITY = 1 << 16;

  // Created on the first exposure: at full capacity its bucket array is 512 KiB, a humongous
  // allocation on a small G1 heap, which a client that records no exposures should not pay for.
  @Nullable private Hashtable.D2<String, String, Exposure> entries;
  private final int capacity;

  // The least and the most recently used entries, the ends of the eviction order.
  private Exposure eldest;
  private Exposure youngest;

  /** Creates a cache remembering up to 65536 subjects, evicting the least recently used ones. */
  public ExposureDeduplicationCache() {
    this(CAPACITY);
  }

  ExposureDeduplicationCache(final int capacity) {
    this.capacity = capacity;
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
    final Exposure exposure = this.entries == null ? null : this.entries.get(flag, subject);
    if (exposure == null) {
      return false;
    }
    touch(exposure);
    return exposure.is(allocation, variant, serialId);
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
    Hashtable.D2<String, String, Exposure> entries = this.entries;
    if (entries == null) {
      entries = this.entries = Hashtable.D2.createBounded(Exposure.class, this.capacity);
    }
    Exposure exposure = entries.get(flag, subject);
    if (exposure != null) {
      touch(exposure);
      if (exposure.is(allocation, variant, serialId)) {
        return false;
      }
      exposure.allocation = allocation;
      exposure.variant = variant;
      exposure.serialId = serialId;
      return true;
    }
    if (entries.size() >= this.capacity) {
      final Exposure evicted = this.eldest;
      if (evicted == null) {
        return true; // a cache without capacity remembers nothing
      }
      unlink(evicted);
      entries.remove(evicted.key1(), evicted.key2());
    }
    exposure = new Exposure(flag, subject, allocation, variant, serialId);
    entries.insert(exposure);
    append(exposure);
    return true;
  }

  /**
   * Gets the number of subjects remembered.
   *
   * @return the number of (flag, subject) entries.
   */
  public synchronized int size() {
    return this.entries == null ? 0 : this.entries.size();
  }

  /** Forgets every recorded exposure. */
  public synchronized void clear() {
    if (this.entries != null) {
      this.entries.clear();
    }
    this.eldest = null;
    this.youngest = null;
  }

  /** Makes an entry the most recently used. */
  private void touch(final Exposure exposure) {
    if (exposure != this.youngest) {
      unlink(exposure);
      append(exposure);
    }
  }

  private void append(final Exposure exposure) {
    exposure.older = this.youngest;
    exposure.newer = null;
    if (this.youngest == null) {
      this.eldest = exposure;
    } else {
      this.youngest.newer = exposure;
    }
    this.youngest = exposure;
  }

  private void unlink(final Exposure exposure) {
    if (exposure.older == null) {
      this.eldest = exposure.newer;
    } else {
      exposure.older.newer = exposure.newer;
    }
    if (exposure.newer == null) {
      this.youngest = exposure.older;
    } else {
      exposure.newer.older = exposure.older;
    }
    exposure.older = null;
    exposure.newer = null;
  }

  /** The last exposure of a (flag, subject), linked into the eviction order. */
  private static final class Exposure extends Hashtable.D2.Entry<String, String> {
    String allocation;
    String variant;
    Integer serialId;
    Exposure older;
    Exposure newer;

    Exposure(
        final String flag,
        final String subject,
        final String allocation,
        final String variant,
        final Integer serialId) {
      super(flag, subject);
      this.allocation = allocation;
      this.variant = variant;
      this.serialId = serialId;
    }

    boolean is(final String allocation, final String variant, final Integer serialId) {
      return Objects.equals(this.allocation, allocation)
          && Objects.equals(this.variant, variant)
          && Objects.equals(this.serialId, serialId);
    }
  }
}
