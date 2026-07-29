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

  private final int initialCapacity;
  private Object[] data = EmbeddingSupport.EMPTY_DATA;

  public LightStringMap(int capacity) {
    this.initialCapacity = capacity;
  }

  public void set(@Nonnull String key, @Nonnull V value) {
    Objects.requireNonNull(value, "value");
    this.data = EmbeddingSupport.set(this.initialCapacity, this.data, key, value);
  }

  @Nullable
  public V get(@Nonnull String key) {
    return EmbeddingSupport.get(this.data, key);
  }

  public void remove(@Nonnull String key) {
    EmbeddingSupport.remove(this.data, key);
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

  public static final class EmbeddingSupport {
    public static final int NOT_FOUND = Integer.MIN_VALUE;
    static final int NO_SPACE = Integer.MIN_VALUE;

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
      if (checkedInsert(mapData, numSlots, key, value)) {
        return mapData;
      }

      mapData = expandMapData(mapData);
      numSlots = numSlots(mapData);
      newMapUncheckedInsert(mapData, numSlots, key, value);
      return mapData;
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
