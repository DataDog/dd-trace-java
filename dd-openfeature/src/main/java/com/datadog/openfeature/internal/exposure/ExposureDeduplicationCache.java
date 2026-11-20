package com.datadog.openfeature.internal.exposure;

import datadog.trace.util.LruTable;
import java.util.Objects;

/**
 * Remembers the last exposure recorded for each (flag, subject). An exposure is sent again only
 * when its allocation, variant or serial id changes. Thread-safe.
 *
 * <p>Each subject is one table entry, keyed by both parts without a key object, updated in place
 * and linked into the least-recently-used order, so a lookup allocates nothing and a full cache
 * holds one object per subject. The table starts small and grows as subjects arrive, so a cache
 * that sees few subjects stays small.
 */
public final class ExposureDeduplicationCache {
  static final int CAPACITY = 1 << 16;

  private final LruTable.D2<String, String, Exposure> entries;

  /** Creates a cache remembering up to 65536 subjects, evicting the least recently used ones. */
  public ExposureDeduplicationCache() {
    this(CAPACITY);
  }

  ExposureDeduplicationCache(final int capacity) {
    this.entries = new LruTable.D2<>(capacity);
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
    final Exposure exposure = this.entries.get(flag, subject);
    return exposure != null && exposure.is(allocation, variant, serialId);
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
    final Exposure exposure = this.entries.get(flag, subject);
    if (exposure != null) {
      if (exposure.is(allocation, variant, serialId)) {
        return false;
      }
      exposure.allocation = allocation;
      exposure.variant = variant;
      exposure.serialId = serialId;
      return true;
    }
    this.entries.insert(new Exposure(flag, subject, allocation, variant, serialId));
    return true;
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

  /** The last exposure of a (flag, subject). */
  private static final class Exposure extends LruTable.D2.Entry<String, String> {
    String allocation;
    String variant;
    Integer serialId;

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
