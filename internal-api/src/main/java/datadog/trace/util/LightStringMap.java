package datadog.trace.util;

import datadog.trace.api.function.TriConsumer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A lightweight {@link String}-keyed map, designed to be small and fast for tiny maps.
 *
 * <p>Supports the common map operations -- {@code set}, {@code get}, {@code remove}, {@code
 * containsKey}, and {@code forEach} -- as an easy, largely footgun-free stand-in wherever a small
 * {@code Map<String, V>} is needed. It deliberately does <em>not</em> implement {@link
 * java.util.Map}; the surface is intentionally small.
 *
 * <p>Neither null keys nor null values are supported: {@code set} rejects a null value, so a null
 * {@code get} result unambiguously means "absent". Use {@link #containsKey} if you need to probe
 * for presence separately. Interned/literal keys resolve slightly faster but are not assumed.
 *
 * <p>This class is <em>not</em> thread-safe.
 *
 * <p>Map data is stored in a single flat array that can be embedded into another object via {@link
 * EmbeddingSupport} as a further optimization.
 */
/*
 * Keys are stored in the first half of the array and values in the second half.
 * Key collisions are resolved via linear probing.
 *
 * This layout is intended to optimize scanning for available and matching slots.
 *
 * Key removal is handled by placing a poison key in the previously occupied slot.
 * This approach was chosen so that linear probing can break out of the loop
 * when an empty slot is encountered.
 *
 * Insertions after the a removal can fill the emptied slot.
 * In the event of resizing, removal markers are discarded while assigning
 * slots in the new data array.
 */
public final class LightStringMap<V> {
  public static final int DEFAULT_CAPACITY = 8;

  // Slots a fresh (un-tuned) hint seeds -- a reasonable default so a cold site behaves like a
  // plain new LightStringMap(DEFAULT_CAPACITY); it then self-tunes up or down from here.
  static final int DEFAULT_HINT_SLOTS = DEFAULT_CAPACITY;
  // Floor step-down never drops a hint below (in slots), so a genuinely tiny site can still tune
  // below the default. Must stay >= 1 (a zero-slot table is degenerate).
  static final int MIN_HINT_SLOTS = 1;
  // Step the learned estimate down one power-of-two class every this-many constructions. A
  // power of two so the tick test is a bit-mask. Large => decay is a slow background correction,
  // not something that fights a steady workload.
  static final int DECAY_INTERVAL = 1024;
  // Safety ceiling on how large a hint will pre-provision (in slots). Bounds the shared hint's
  // over-provision from an outlier; the map itself is uncapped and grows past this on its own.
  static final int MAX_HINT_SLOTS = 1024;

  private final int initialCapacity;
  @Nullable private final SizingHint sizingHint;
  private Object[] data = EmbeddingSupport.EMPTY_DATA;

  public LightStringMap(int capacity) {
    this.initialCapacity = capacity;
    this.sizingHint = null;
  }

  public LightStringMap(@Nonnull SizingHint hint) {
    this.sizingHint = hint;
    this.initialCapacity = hint.seedSlots();
  }

  /**
   * Mints a self-tuning {@link SizingHint} for a single construction site. Hold it in a {@code
   * static final} field and pass it to every {@link #LightStringMap(SizingHint)} at that site; the
   * map sizes itself from the hint and tunes the hint back on its own. The caller never touches the
   * hint again.
   */
  @Nonnull
  public static SizingHint sizingHint() {
    return new SizingHint();
  }

  public void set(@Nonnull String key, @Nonnull V value) {
    Objects.requireNonNull(value, "value");

    Object[] before = this.data;
    int beforeSlots = EmbeddingSupport.numSlots(before);
    // The spine owns the grow decision (probe-bound trigger) and returns the map data, resized if
    // it grew. The object is a thin delegate: its only extra job is to teach the sizing hint.
    Object[] after = EmbeddingSupport.set(this.initialCapacity, before, key, value);
    this.data = after;

    // Record a genuine grow (not the lazy first allocation, which seeds from beforeSlots == 0) so
    // the hint learns this site's high-water mark. seedSlots()/newMapData never feed the hint.
    if (this.sizingHint != null && beforeSlots != 0) {
      int afterSlots = EmbeddingSupport.numSlots(after);
      if (afterSlots > beforeSlots) {
        this.sizingHint.recordSlots(afterSlots);
      }
    }
  }

  @Nullable
  public V get(@Nonnull String key) {
    return EmbeddingSupport.get(this.data, key);
  }

  public void remove(@Nonnull String key) {
    EmbeddingSupport.remove(this.data, key);
  }

  /** The number of live entries in this map (tombstones excluded). */
  public int size() {
    return EmbeddingSupport.size(this.data);
  }

  public boolean containsKey(@Nonnull String key) {
    return EmbeddingSupport.containsKey(this.data, key);
  }

  @SuppressWarnings("unchecked")
  public void forEach(@Nonnull BiConsumer<? super String, ? super V> consumer) {
    Object[] mapData = this.data;
    if (mapData == null) return;
    int numSlots = mapData.length >> 1;
    for (int slot = 0; slot < numSlots; slot++) {
      String key = (String) mapData[slot];
      if (key == null || EmbeddingSupport.isRemoved(key)) continue;
      consumer.accept(key, (V) mapData[slot + numSlots]);
    }
  }

  // Visible for testing: the backing spine (null until the first set).
  @Nullable
  Object[] dataForTesting() {
    return this.data;
  }

  /**
   * A self-tuning, per-construction-site sizing estimate. Mint one via {@link
   * LightStringMap#sizingHint()}, hold it in a {@code static final} field, and pass it to {@link
   * LightStringMap#LightStringMap(SizingHint)}; the map reads it to size itself and tunes it back
   * as it grows. The caller never updates it.
   *
   * <p>Opaque by design (no public members). The estimate self-tunes on two events the map already
   * observes -- a new map is started ({@link #seedSlots()}) and a map grows ({@link
   * #recordSlots(int)}) -- so it is tier-agnostic: the same hint can drive both this object and the
   * static {@link EmbeddingSupport} spine.
   *
   * <p>Tuning is racy by design: {@code slots}/{@code constructs} are plain ints, so a lost or torn
   * update only mis-sizes a future array (over/under-provision) for an instance or two, never
   * corrupts map data -- no synchronization.
   */
  public static final class SizingHint {
    // Learned seed capacity in slots (always a power of two). Additive-increase on grow (with one
    // class of headroom), multiplicative-decrease on the decay tick.
    private int slots = DEFAULT_HINT_SLOTS;
    // Approximate count of maps started from this hint; drives the periodic step-down decay.
    private int constructs;

    private SizingHint() {}

    /**
     * The seed capacity (in slots) for a map just started from this hint. Advances the decay clock
     * and, every {@link #DECAY_INTERVAL} maps, steps the estimate down one class so a stale
     * high-water from a past spike self-corrects; if that made it too tight, the next grow snaps it
     * back.
     */
    int seedSlots() {
      int n = ++this.constructs; // racy; a torn read only jitters the decay cadence
      if ((n & (DECAY_INTERVAL - 1)) == 0) {
        int reduced = this.slots >> 1;
        this.slots = (reduced < MIN_HINT_SLOTS) ? MIN_HINT_SLOTS : reduced;
      }
      return this.slots;
    }

    /**
     * Records that a map grew to {@code grownSlots}. Reserves one extra power-of-two class so a
     * reseeded map starts with slack and is unlikely to immediately re-trip the grow trigger for
     * the same workload. Monotonic-max, clamped to {@link #MAX_HINT_SLOTS}.
     */
    void recordSlots(int grownSlots) {
      int candidate = grownSlots << 1;
      if (candidate > MAX_HINT_SLOTS) {
        candidate = MAX_HINT_SLOTS;
      }
      if (candidate > this.slots) {
        this.slots = candidate;
      }
    }

    // Visible for testing: the current learned seed capacity in slots.
    int currentSeedSlots() {
      return this.slots;
    }
  }

  public static final class EmbeddingSupport {
    public static final int NOT_FOUND = Integer.MIN_VALUE;
    static final int NO_SPACE = Integer.MIN_VALUE;

    // Grow trigger: an insertion that would land this many slots or more from its home slot forces
    // a resize, rather than waiting for the table to fill completely. This bounds the worst-case
    // probe length (and therefore lookup cost) directly -- a load-factor threshold cannot, because
    // it is blind to local clustering (colliding keys pile into one chain long before global
    // occupancy is high). The check is derived entirely from the probe walk, so it is stateless and
    // lives here in the spine rather than needing a maintained live count in the object tier.
    //
    // Chosen as a power-of-two-friendly 8 from measurement (LightStringMapGrowBenchmark): it caps
    // the worst-case probe at 8 while keeping the memory over-provision modest on well-spread keys.
    // Because a table never has more than (numSlots - 1) probe distance, this is inert for tables
    // of
    // 8 slots or fewer -- tiny maps still grow only when physically full, exactly as before.
    static final int MAX_PROBES = 8;

    // TODO: use of String constructor is deliberate, since this is
    // an internal marker that we don't want intern-ed
    static final String REMOVED = new String("\0D\0a\07\04\0\0d\00\0G");

    @Nullable public static final Object[] EMPTY_DATA = null;

    public static boolean isDefinitelyEmpty(@Nullable Object[] mapData) {
      return (mapData == null);
    }

    public static int numSlots(@Nullable Object[] mapData) {
      return mapData == null ? 0 : mapData.length >> 1;
    }

    public static int size(@Nullable Object[] mapData) {
      if (mapData == null) return 0;

      int size = 0;
      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        Object key = mapData[slot];
        if (key != null && key != REMOVED) size += 1;
      }
      return size;
    }

    @Nullable
    public static Object[] clear(@Nullable Object[] mapData) {
      if (mapData != null) {
        Arrays.fill(mapData, null);
      }
      return mapData;
    }

    @Nullable
    public static Object[] copy(@Nullable Object[] mapData) {
      return mapData == null ? null : mapData.clone();
    }

    public static boolean isPresent(int slotIndex) {
      return (slotIndex >= 0);
    }

    public static boolean isRemoved(@Nonnull String key) {
      return (key == REMOVED);
    }

    @Nullable
    public static String keyAt(@Nullable Object[] mapData, int slotIndex) {
      if (mapData == null) return null;
      if (slotIndex < 0) return null;

      String key = str(mapData[slotIndex]);
      return (key == REMOVED) ? null : key;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> V valueAt(@Nullable Object[] mapData, int slotIndex) {
      if (mapData == null || slotIndex < 0) return null;

      return (V) mapData[slotIndex + numSlots(mapData)];
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> V prevValueAt(@Nullable Object[] mapData, int slotIndex) {
      if (mapData == null || slotIndex < 0) return null;

      return (V) mapData[slotIndex + numSlots(mapData)];
    }

    public static final boolean containsKey(@Nullable Object[] mapData, @Nonnull String key) {
      if (mapData == null) return false;

      // not bothering with optimizing literal checks
      int numSlots = numSlots(mapData);

      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      // TODO: check whether fast literal search is worth it
      for (int slot = preferredSlot; slot < numSlots; ++slot) {
        String curKey = str(mapData[slot]);
        if (curKey == null) return false;
        if (curKey != REMOVED && key.equals(curKey)) return true;
      }
      for (int slot = 0; slot < preferredSlot; ++slot) {
        String curKey = str(mapData[slot]);
        if (curKey == null) return false;
        if (curKey != REMOVED && key.equals(curKey)) return true;
      }
      return false;
    }

    public static final boolean containsValue(@Nullable Object[] mapData, @Nonnull Object value) {
      if (mapData == null) return false;

      for (int valueIndex = numSlots(mapData); valueIndex < mapData.length; ++valueIndex) {
        Object curValue = mapData[valueIndex];
        if (value.equals(curValue)) return true;
      }
      return false;
    }

    @Nonnull
    public static <V> Object[] set(
        @Nullable Object[] mapData, @Nonnull String key, @Nonnull V value) {
      return set(DEFAULT_CAPACITY, mapData, key, value);
    }

    @Nullable
    public static <V> Object[] setAll(
        @Nullable Object[] destMapData, @Nullable Object[] srcMapData) {
      return setAll(destMapData, size(destMapData), srcMapData, size(srcMapData));
    }

    @Nullable
    public static <V> Object[] setAll(
        @Nullable Object[] destMapData, int destSize, @Nullable Object[] srcMapData, int srcSize) {
      if (srcMapData == null || srcSize == 0) return destMapData;
      if (destMapData == null) return srcMapData.clone();

      int expectedSize = destSize + srcSize;
      int initDestSlots = numSlots(destMapData);
      int initFreeSpace = initDestSlots - destSize;

      int numDestSlots;
      if (expectedSize > initDestSlots) {
        destMapData = expandMapData(destMapData, expectedSize + Math.max(initFreeSpace, 10));
        numDestSlots = numSlots(destMapData); // re-read after expandMapData rounds to pow-of-2
      } else {
        numDestSlots = initDestSlots;
      }

      int numSrcSlots = numSlots(srcMapData);
      for (int srcSlot = 0; srcSlot < numSrcSlots; ++srcSlot) {
        String srcKey = str(srcMapData[srcSlot]);
        if (srcKey == null || srcKey == REMOVED) continue;

        Object srcValue = srcMapData[srcSlot + numSrcSlots];
        checkedInsert(destMapData, numDestSlots, srcKey, srcValue);
      }

      return destMapData;
    }

    @Nonnull
    public static <V> Object[] set(
        int initialCapacity, @Nullable Object[] mapData, @Nonnull String key, @Nonnull V value) {
      if (mapData == null) {
        int numSlots = roundUpToPow2(initialCapacity);
        mapData = new Object[numSlots << 1];

        int keyIndex = preferredSlot(numSlots, key.hashCode());
        mapData[keyIndex] = key;
        mapData[keyIndex + numSlots] = value;
        return mapData;
      }

      int numSlots = numSlots(mapData);
      int slot = findInsertionSlot(mapData, numSlots, key);
      if (slot >= 0) {
        // Key already present -- overwrite in place, no growth.
        mapData[slot + numSlots] = value;
        return mapData;
      }
      if (slot != NO_SPACE && withinProbeBound(numSlots, slot, key)) {
        // A free slot within the probe bound -- fill it directly.
        int availableSlot = flip(slot);
        mapData[availableSlot] = key;
        mapData[availableSlot + numSlots] = value;
        return mapData;
      }

      // No space at all, or the insertion would exceed the probe bound: grow, then insert into the
      // fresh (tombstone-free, better-spread) table.
      mapData = expandMapData(mapData);
      newMapUncheckedInsert(mapData, numSlots(mapData), key, value);
      return mapData;
    }

    // Whether an insertion at the free slot encoded by {@code insertionSlot} (as returned by
    // findInsertionSlot for an absent key) lands within {@link #MAX_PROBES} of the key's home slot.
    private static boolean withinProbeBound(int numSlots, int insertionSlot, String key) {
      int availableSlot = flip(insertionSlot);
      int home = preferredSlot(numSlots, key.hashCode());
      int distance = (availableSlot - home) & (numSlots - 1);
      return distance < MAX_PROBES;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <V> V get(@Nullable Object[] mapData, @Nonnull String key) {
      if (mapData == null) return null;

      int numSlots = numSlots(mapData);
      int foundIndex = findSlot(mapData, numSlots, key);

      return (foundIndex >= 0) ? (V) mapData[numSlots + foundIndex] : null;
    }

    public static boolean remove(@Nullable Object[] mapData, @Nonnull String key) {
      if (mapData == null) return false;

      int numSlots = numSlots(mapData);
      int foundIndex = findSlot(mapData, numSlots, key);
      if (foundIndex >= 0) {
        mapData[foundIndex] = REMOVED;
        mapData[foundIndex + numSlots] = null;

        return true;
      } else {
        return false;
      }
    }

    public static boolean checkedInsert(
        @Nonnull Object[] mapData, int numSlots, @Nonnull String key, @Nonnull Object value) {
      int insertionSlot = findInsertionSlot(mapData, numSlots, key);

      if (insertionSlot >= 0) {
        mapData[insertionSlot + numSlots] = value;
        return true;
      } else if (insertionSlot != NO_SPACE) {
        int availableSlot = flip(insertionSlot);
        mapData[availableSlot] = key;
        mapData[availableSlot + numSlots] = value;
        return true;
      } else {
        return false;
      }
    }

    public static <T> int findInsertionSlot(@Nullable Object[] mapData, @Nonnull String key) {
      if (mapData == null) return NO_SPACE;

      return findInsertionSlot(mapData, numSlots(mapData), key);
    }

    @Nonnull
    public static final Object[] insertAt(
        int initialCapacity,
        @Nullable Object[] mapData,
        int insertionSlot,
        @Nonnull String key,
        @Nonnull Object value) {
      if (mapData == null) {
        return newMapData(initialCapacity, key, value);
      }

      if (insertionSlot == NO_SPACE) {
        mapData = expandMapData(mapData);
        newMapUncheckedInsert(mapData, numSlots(mapData), key, value);
      } else {
        if (insertionSlot < 0) insertionSlot = flip(insertionSlot);

        mapData[insertionSlot] = key;
        mapData[insertionSlot + numSlots(mapData)] = value;
      }

      return mapData;
    }

    static final <T> int findInsertionSlot(Object[] mapData, int numSlots, String key) {
      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      int availableIndex = NO_SPACE;
      for (int keyIndex = preferredSlot; keyIndex < numSlots; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null) return flip(keyIndex);
        if (curKey == key) return keyIndex;
        if (curKey == REMOVED) {
          if (availableIndex == NO_SPACE) availableIndex = flip(keyIndex);
        } else if (key.equals(curKey)) return keyIndex;
      }
      for (int keyIndex = 0; keyIndex < preferredSlot; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null) return flip(keyIndex);
        if (curKey == key) return keyIndex;
        if (curKey == REMOVED) {
          if (availableIndex == NO_SPACE) availableIndex = flip(keyIndex);
        } else if (key.equals(curKey)) return keyIndex;
      }
      return availableIndex;
    }

    static int flip(int keyIndex) {
      return -keyIndex - 1;
    }

    public static final void removeAt(@Nullable Object[] mapData, int slot) {
      if (mapData == null || slot < 0) return;

      mapData[slot] = REMOVED;
      mapData[slot + numSlots(mapData)] = null;
    }

    @Nullable
    public static final Object getAndRemoveAt(@Nullable Object[] mapData, int slot) {
      if (mapData == null || slot < 0) return null;

      mapData[slot] = REMOVED;

      int valueIndex = slot + numSlots(mapData);
      Object prev = mapData[valueIndex];
      mapData[valueIndex] = null;

      return prev;
    }

    public static final int findSlot(@Nullable Object[] mapData, @Nonnull String key) {
      if (mapData == null) return NOT_FOUND;

      return findSlot(mapData, numSlots(mapData), key);
    }

    static final int findSlot(Object[] mapData, int numSlots, String key) {
      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      // Single equals-based probe that terminates at the first null slot. We do not
      // assume interned keys, so there is no separate ref-equality pre-pass: String.equals
      // already short-circuits on `this == other`, so a literal-key hit still resolves on a
      // pointer compare, while a miss touches only the probe chain (not the whole array).
      for (int keyIndex = preferredSlot; keyIndex < numSlots; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null) return -1;
        if (curKey != REMOVED && key.equals(curKey)) return keyIndex;
      }
      for (int keyIndex = 0; keyIndex < preferredSlot; ++keyIndex) {
        Object curKey = mapData[keyIndex];
        if (curKey == null) return -1;
        if (curKey != REMOVED && key.equals(curKey)) return keyIndex;
      }
      return -1;
    }

    static final Object[] newMapData(int initialCapacity, String key, Object value) {
      int numSlots = roundUpToPow2(initialCapacity);
      Object[] mapData = new Object[numSlots << 1];

      int slotIndex = preferredSlot(numSlots, key.hashCode());
      mapData[slotIndex] = key;
      mapData[slotIndex + numSlots] = value;
      return mapData;
    }

    @Nonnull
    public static final Object[] expandMapData(@Nonnull Object[] mapData) {
      // Subtle - capacity is in terms of slots - not array size, so passing length is
      // asking to double capacity
      return expandMapData(mapData, mapData.length);
    }

    @Nonnull
    public static Object[] expandMapData(@Nonnull Object[] origMapData, int newCapacity) {
      newCapacity = roundUpToPow2(newCapacity);
      int newSize = newCapacity << 1;
      // Don't try to optimize by returning origMapData if big enough to contain
      // the newCapacity.  There's subtle invariant that new maps also don't
      // contain remove markers that must also be maintained.

      int origNumSlots = numSlots(origMapData);

      Object[] newMapData = new Object[newSize];
      for (int slot = 0; slot < origNumSlots; ++slot) {
        String key = str(origMapData[slot]);
        if (key == null || key == REMOVED) continue;

        Object value = origMapData[slot + origNumSlots];
        newMapUncheckedInsert(newMapData, newCapacity, key, value);
      }
      return newMapData;
    }

    public static void forEach(
        @Nullable Object[] mapData, @Nonnull BiConsumer<String, Object> entryConsumer) {
      if (mapData == null) return;

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        String key = str(mapData[slot]);
        if (key == null || key == REMOVED) continue;

        Object value = mapData[slot + numSlots];
        entryConsumer.accept(key, value);
      }
    }

    public static <C> void forEach(
        @Nullable Object[] mapData, C ctx, @Nonnull TriConsumer<C, String, Object> entryConsumer) {
      if (mapData == null) return;

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        String key = str(mapData[slot]);
        if (key == null || key == REMOVED) continue;

        Object value = mapData[slot + numSlots];
        entryConsumer.accept(ctx, key, value);
      }
    }

    @Nonnull
    public static String toInternalString(@Nullable Object[] mapData) {
      StringBuilder builder = new StringBuilder(128);
      builder.append('{');

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        String key = str(mapData[slot]);
        if (key == null) continue;

        builder.append('[').append(slot).append("]=");

        if (key == REMOVED) {
          builder.append("--REMOVED--");
        } else {
          Object value = mapData[slot + numSlots];
          builder.append(key).append(':').append(value);
        }
        builder.append('\n');
      }
      builder.append('}');
      return builder.toString();
    }

    static void newMapUncheckedInsert(Object[] mapData, int numSlots, String key, Object value) {
      int hash = key.hashCode();
      int preferredSlot = preferredSlot(numSlots, hash);

      for (int slot = preferredSlot; slot < numSlots; ++slot) {
        Object curKey = mapData[slot];
        if (curKey == null) {
          mapData[slot] = key;
          mapData[slot + numSlots] = value;

          return;
        }
      }
      for (int slot = 0; slot < preferredSlot; ++slot) {
        Object curKey = mapData[slot];
        if (curKey == null) {
          mapData[slot] = key;
          mapData[slot + numSlots] = value;

          return;
        }
      }
    }

    static int preferredSlot(int numSlots, int hash) {
      // numSlots is required to be a power of 2 (allocation sites round up via roundUpToPow2).
      // The bit mask is ~20x faster than a modulo divide on typical hardware.
      return (hash ^ (hash >>> 16)) & (numSlots - 1);
    }

    static int roundUpToPow2(int n) {
      return n <= 1 ? 1 : Integer.highestOneBit(n - 1) << 1;
    }

    static final String str(Object key) {
      return (String) key;
    }
  }
}
