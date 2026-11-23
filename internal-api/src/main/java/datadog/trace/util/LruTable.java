package datadog.trace.util;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bounded least-recently-used tables that allocate nothing per lookup and nothing per entry beyond
 * the entry itself. The entries are the list: each carries its own links in the recency order, so a
 * full table holds one object per key, where a {@code LinkedHashMap} in access order holds a node
 * per key plus whatever key and value objects the caller builds.
 *
 * <p>The caller supplies an entry subclass with the key and whatever value fields it mutates in
 * place, as with {@link Hashtable.D1} and {@link Hashtable.D2}. At its bound, {@code insert} evicts
 * the least recently used entry and returns it, so a caller can release what it holds or count the
 * eviction.
 *
 * <p>The bucket array is sized exactly as {@link Hashtable.D1}'s is. By default it is allocated
 * once, at {@link Hashtable#capacityFor(int)} of the bound. A {@link Hashtable.Sizing} with an
 * initial capacity opts into growth through {@link Hashtable#resize(Hashtable.Entry[])}: the array
 * starts at a quarter of that size at least, doubles once an insert would take it past 3/4 full,
 * and stops at the full size, so it grows at most twice. Opt in for a long-lived table with a
 * generous bound that may stay sparse. Growth relinks the bucket chains only; the recency links are
 * separate fields and stay as they are.
 *
 * <p>This outer class is a namespace. Not thread-safe: guard a shared table with a lock.
 */
public final class LruTable {
  private LruTable() {}

  /**
   * Base class of entries: the chained-bucket entry plus its links in the recency order. Subclass
   * {@link D1.Entry} or {@link D2.Entry} rather than this.
   */
  public abstract static class Entry extends Hashtable.Entry {
    Entry older;
    Entry newer;

    Entry(final long keyHash) {
      super(keyHash);
    }
  }

  /**
   * The bucket array and the recency list, shared by {@link D1} and {@link D2}. The list runs from
   * {@code eldest} to {@code youngest} through {@link Entry#newer}. Not subclassable outside this
   * package.
   *
   * @param <TEntry> the entry type
   */
  public abstract static class Base<TEntry extends Entry> {
    private final int maxSize;
    private final int maxBuckets;
    Hashtable.Entry[] buckets;
    private int size;
    private Entry eldest;
    private Entry youngest;

    Base(@Nonnull final Hashtable.Sizing sizing) {
      this.maxSize = sizing.maxCapacity();
      this.maxBuckets = Hashtable.capacityFor(maxSize);
      this.buckets = Hashtable.create(Hashtable.initialBucketsFor(sizing));
    }

    /**
     * Gets the number of entries.
     *
     * @return the live entry count, at most the bound.
     */
    public final int size() {
      return size;
    }

    /**
     * Gets the bound.
     *
     * @return the most entries the table holds.
     */
    public final int maxSize() {
      return maxSize;
    }

    /**
     * Gets the least recently used entry, the next one {@code insert} evicts at the bound.
     *
     * @return the eldest entry, or {@code null} when empty.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public final TEntry eldest() {
      return (TEntry) eldest;
    }

    /**
     * Links a new entry as the most recently used. The caller must have checked that no entry with
     * the same key is present, typically with {@code get} on the same miss.
     *
     * @param entry the new entry, not linked in any table.
     * @return the evicted least recently used entry when the table was full; {@code entry} itself
     *     when the bound is 0; otherwise {@code null}.
     */
    @Nullable
    public final TEntry insert(@Nonnull final TEntry entry) {
      if (maxSize == 0) {
        return entry; // a table without room remembers nothing
      }
      Entry evicted = null;
      if (size == maxSize) {
        evicted = eldest;
        unlinkEntry(evicted);
      } else if (Hashtable.needsGrowth(size, buckets.length, maxBuckets)) {
        buckets = Hashtable.resize(buckets);
      }
      Hashtable.insertHeadEntryFor(buckets, entry.keyHash, entry);
      append(entry);
      size++;
      return cast(evicted);
    }

    /** Removes every entry. The bucket array keeps its size. */
    public final void clear() {
      Arrays.fill(buckets, null);
      for (Entry e = eldest; e != null; ) {
        final Entry next = e.newer;
        e.older = null;
        e.newer = null;
        e.setNext(null);
        e = next;
      }
      eldest = null;
      youngest = null;
      size = 0;
    }

    /**
     * Visits the entries from the least to the most recently used, without reordering them.
     *
     * @param consumer receives each entry; it must not modify the table.
     */
    public final void forEach(@Nonnull final Consumer<? super TEntry> consumer) {
      for (Entry e = eldest; e != null; e = e.newer) {
        consumer.accept(cast(e));
      }
    }

    /** The default sizing for a bound, keeping the constructors' own argument check. */
    static Hashtable.Sizing sized(final int maxSize) {
      if (maxSize < 0) {
        throw new IllegalArgumentException("maxSize < 0: " + maxSize);
      }
      return Hashtable.Sizing.bounded(maxSize);
    }

    @SuppressWarnings("unchecked")
    static <TEntry extends Entry> TEntry cast(final Entry entry) {
      return (TEntry) entry;
    }

    /** Makes an entry the most recently used. */
    final void touch(final Entry entry) {
      if (entry != youngest) {
        unlinkFromList(entry);
        append(entry);
      }
    }

    /** Unlinks an entry from its bucket chain and from the list. */
    final void unlinkEntry(final Entry entry) {
      final int index = Hashtable.bucketIndex(buckets, entry.keyHash);
      Hashtable.Entry prev = null;
      for (Hashtable.Entry e = buckets[index]; e != null; prev = e, e = e.next()) {
        if (e == entry) {
          if (prev == null) {
            buckets[index] = e.next();
          } else {
            prev.setNext(e.next());
          }
          break;
        }
      }
      entry.setNext(null);
      unlinkFromList(entry);
      size--;
    }

    private void append(final Entry entry) {
      entry.older = youngest;
      entry.newer = null;
      if (youngest == null) {
        eldest = entry;
      } else {
        youngest.newer = entry;
      }
      youngest = entry;
    }

    private void unlinkFromList(final Entry entry) {
      if (entry.older == null) {
        eldest = entry.newer;
      } else {
        entry.older.newer = entry.newer;
      }
      if (entry.newer == null) {
        youngest = entry.older;
      } else {
        entry.newer.older = entry.older;
      }
      entry.older = null;
      entry.newer = null;
    }
  }

  /**
   * A bounded LRU table with one key.
   *
   * @param <K> the key type
   * @param <TEntry> the entry type
   */
  public static final class D1<K, TEntry extends D1.Entry<K>> extends Base<TEntry> {
    /**
     * Base class of {@link D1} entries. Subclass to add the value fields you mutate in place.
     *
     * @param <K> the key type
     */
    public abstract static class Entry<K> extends LruTable.Entry {
      final K key;

      protected Entry(@Nullable final K key) {
        super(Hashtable.D1.Entry.hash(key));
        this.key = key;
      }

      /** The key this entry was created with. */
      @Nullable
      public final K key() {
        return key;
      }

      /** Whether this entry's key equals {@code key}; override for another equality. */
      public boolean matches(@Nullable final Object key) {
        return Objects.equals(this.key, key);
      }
    }

    /**
     * Creates a table that holds up to {@code maxSize} entries.
     *
     * @param maxSize the bound; 0 for a table that remembers nothing.
     */
    public D1(final int maxSize) {
      super(sized(maxSize));
    }

    private D1(@Nonnull final Hashtable.Sizing sizing) {
      super(sizing);
    }

    /**
     * Creates a table sized by {@code sizing}: it holds up to the max capacity and its bucket array
     * starts sized for the initial capacity, as with {@link Hashtable.D1#create}.
     *
     * @param entryClass concrete entry type, used only for generic type inference.
     * @param sizing the bound and the starting size, in entries.
     * @return an empty table.
     */
    @Nonnull
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> create(
        @Nonnull final Class<TEntry> entryClass, @Nonnull final Hashtable.Sizing sizing) {
      return new D1<>(sizing);
    }

    /**
     * Gets the entry for a key and makes it the most recently used.
     *
     * @param key the key.
     * @return the entry, or {@code null} when absent.
     */
    @Nullable
    public TEntry get(@Nullable final K key) {
      final TEntry entry = peek(key);
      if (entry != null) {
        touch(entry);
      }
      return entry;
    }

    /**
     * Gets the entry for a key without changing the recency order.
     *
     * @param key the key.
     * @return the entry, or {@code null} when absent.
     */
    @Nullable
    public TEntry peek(@Nullable final K key) {
      final long keyHash = Hashtable.D1.Entry.hash(key);
      for (Hashtable.Entry e = buckets[Hashtable.bucketIndex(buckets, keyHash)];
          e != null;
          e = e.next()) {
        if (e.keyHash == keyHash) {
          final TEntry entry = cast((LruTable.Entry) e);
          if (entry.matches(key)) {
            return entry;
          }
        }
      }
      return null;
    }

    /**
     * Removes the entry for a key.
     *
     * @param key the key.
     * @return the removed entry, or {@code null} when absent.
     */
    @Nullable
    public TEntry remove(@Nullable final K key) {
      final TEntry entry = peek(key);
      if (entry != null) {
        unlinkEntry(entry);
      }
      return entry;
    }
  }

  /**
   * A bounded LRU table with a two-part key, so a lookup builds no key object.
   *
   * @param <K1> the first key type
   * @param <K2> the second key type
   * @param <TEntry> the entry type
   */
  public static final class D2<K1, K2, TEntry extends D2.Entry<K1, K2>> extends Base<TEntry> {
    /**
     * Base class of {@link D2} entries. Subclass to add the value fields you mutate in place.
     *
     * @param <K1> the first key type
     * @param <K2> the second key type
     */
    public abstract static class Entry<K1, K2> extends LruTable.Entry {
      final K1 key1;
      final K2 key2;

      protected Entry(@Nullable final K1 key1, @Nullable final K2 key2) {
        super(Hashtable.D2.Entry.hash(key1, key2));
        this.key1 = key1;
        this.key2 = key2;
      }

      /** The first key part this entry was created with. */
      @Nullable
      public final K1 key1() {
        return key1;
      }

      /** The second key part this entry was created with. */
      @Nullable
      public final K2 key2() {
        return key2;
      }

      /** Whether this entry's key parts equal these; override for another equality. */
      public boolean matches(@Nullable final K1 key1, @Nullable final K2 key2) {
        return Objects.equals(this.key1, key1) && Objects.equals(this.key2, key2);
      }
    }

    /**
     * Creates a table that holds up to {@code maxSize} entries.
     *
     * @param maxSize the bound; 0 for a table that remembers nothing.
     */
    public D2(final int maxSize) {
      super(sized(maxSize));
    }

    private D2(@Nonnull final Hashtable.Sizing sizing) {
      super(sizing);
    }

    /**
     * Creates a table sized by {@code sizing}: it holds up to the max capacity and its bucket array
     * starts sized for the initial capacity, as with {@link Hashtable.D2#create}.
     *
     * @param entryClass concrete entry type, used only for generic type inference.
     * @param sizing the bound and the starting size, in entries.
     * @return an empty table.
     */
    @Nonnull
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> create(
        @Nonnull final Class<TEntry> entryClass, @Nonnull final Hashtable.Sizing sizing) {
      return new D2<>(sizing);
    }

    /**
     * Gets the entry for a key pair and makes it the most recently used.
     *
     * @param key1 the first key part.
     * @param key2 the second key part.
     * @return the entry, or {@code null} when absent.
     */
    @Nullable
    public TEntry get(@Nullable final K1 key1, @Nullable final K2 key2) {
      final TEntry entry = peek(key1, key2);
      if (entry != null) {
        touch(entry);
      }
      return entry;
    }

    /**
     * Gets the entry for a key pair without changing the recency order.
     *
     * @param key1 the first key part.
     * @param key2 the second key part.
     * @return the entry, or {@code null} when absent.
     */
    @Nullable
    public TEntry peek(@Nullable final K1 key1, @Nullable final K2 key2) {
      final long keyHash = Hashtable.D2.Entry.hash(key1, key2);
      for (Hashtable.Entry e = buckets[Hashtable.bucketIndex(buckets, keyHash)];
          e != null;
          e = e.next()) {
        if (e.keyHash == keyHash) {
          final TEntry entry = cast((LruTable.Entry) e);
          if (entry.matches(key1, key2)) {
            return entry;
          }
        }
      }
      return null;
    }

    /**
     * Removes the entry for a key pair.
     *
     * @param key1 the first key part.
     * @param key2 the second key part.
     * @return the removed entry, or {@code null} when absent.
     */
    @Nullable
    public TEntry remove(@Nullable final K1 key1, @Nullable final K2 key2) {
      final TEntry entry = peek(key1, key2);
      if (entry != null) {
        unlinkEntry(entry);
      }
      return entry;
    }
  }
}
