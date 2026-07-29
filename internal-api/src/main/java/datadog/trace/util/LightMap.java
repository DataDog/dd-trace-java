package datadog.trace.util;

import datadog.trace.api.function.TriConsumer;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * Implements a Map<String, Object>-like interface
 *
 * <p>Designed to be light weight and fast for tiny maps -- Especially when the keys are likely to
 * be string literals
 *
 * <p>Map data is stored in a single flat array that can be embedded into another object via
 * EmbeddingSupport as a further optimization.
 */
/*
 * Keys are stored in the first half of the array and values in the second half.
 * Key collisions are resolved via linear probing.
 *
 * This layout is intended to optimize scanning for available and matching slots --
 * especially in the common case where the keys are string literals.
 *
 * Key removal is handled by placing a poison key in the previously occupied slot.
 * This approach was chosen so that linear probing can break out of the loop
 * when an empty slot is encountered.
 *
 * Insertions after the a removal can fill the emptied slot.
 * In the event of resizing, removal markers are discarded while assigning
 * slots in the new data array.
 */
public final class LightMap<V> {
  public static final int DEFAULT_CAPACITY = 8;

  private final int initialCapacity;
  private Object[] data = EmbeddingSupport.EMPTY_DATA;

  public LightMap(int capacity) {
    this.initialCapacity = capacity;
  }

  public void set(String literal, V value) {
    this.data = EmbeddingSupport.set(this.initialCapacity, this.data, literal, value);
  }

  public V get(String literal) {
    return EmbeddingSupport.get(this.data, literal);
  }

  public void remove(String literal) {
    EmbeddingSupport.remove(this.data, literal);
  }

  @SuppressWarnings("unchecked")
  public void forEach(java.util.function.BiConsumer<? super String, ? super V> consumer) {
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

    public static final Object[] EMPTY_DATA = null;

    public static boolean isDefinitelyEmpty(Object[] mapData) {
      return (mapData == null);
    }

    public static int numSlots(Object[] mapData) {
      return mapData == null ? 0 : mapData.length >> 1;
    }

    public static int size(Object[] mapData) {
      if (mapData == null) return 0;

      int size = 0;
      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        Object key = mapData[slot];
        if (key != null && key != REMOVED) size += 1;
      }
      return size;
    }

    public static Object[] clear(Object[] mapData) {
      if (mapData != null) {
        Arrays.fill(mapData, null);
      }
      return mapData;
    }

    public static Object[] copy(Object[] mapData) {
      return mapData == null ? null : mapData.clone();
    }

    public static boolean isPresent(int slotIndex) {
      return (slotIndex >= 0);
    }

    public static boolean isRemoved(String key) {
      return (key == REMOVED);
    }

    public static String keyAt(Object[] mapData, int slotIndex) {
      if (mapData == null) return null;
      if (slotIndex < 0) return null;

      String key = str(mapData[slotIndex]);
      return (key == REMOVED) ? null : key;
    }

    @SuppressWarnings("unchecked")
    public static <V> V valueAt(Object[] mapData, int slotIndex) {
      if (mapData == null) return null;

      return (V) mapData[slotIndex + numSlots(mapData)];
    }

    @SuppressWarnings("unchecked")
    public static <V> V prevValueAt(Object[] mapData, int slotIndex) {
      if (mapData == null || slotIndex < 0) return null;

      return (V) mapData[slotIndex + numSlots(mapData)];
    }

    public static final boolean containsKey(Object[] mapData, String key) {
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

    public static final boolean containsValue(Object[] mapData, Object value) {
      if (mapData == null) return false;

      for (int valueIndex = numSlots(mapData); valueIndex < mapData.length; ++valueIndex) {
        Object curValue = mapData[valueIndex];
        if (value.equals(curValue)) return true;
      }
      return false;
    }

    public static <V> Object[] set(Object[] mapData, String key, V value) {
      return set(DEFAULT_CAPACITY, mapData, key, value);
    }

    public static <V> Object[] setAll(Object[] destMapData, Object[] srcMapData) {
      return setAll(destMapData, size(destMapData), srcMapData, size(srcMapData));
    }

    public static <V> Object[] setAll(
        Object[] destMapData, int destSize, Object[] srcMapData, int srcSize) {
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

    public static <V> Object[] set(int initialCapacity, Object[] mapData, String key, V value) {
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
    public static <V> V get(Object[] mapData, String key) {
      if (mapData == null) return null;

      int numSlots = numSlots(mapData);
      int foundIndex = findSlot(mapData, numSlots, key);

      return (foundIndex >= 0) ? (V) mapData[numSlots + foundIndex] : null;
    }

    public static boolean remove(Object[] mapData, String key) {
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

    public static boolean checkedInsert(Object[] mapData, int numSlots, String key, Object value) {
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

    public static <T> int findInsertionSlot(Object[] mapData, String key) {
      if (mapData == null) return NO_SPACE;

      return findInsertionSlot(mapData, numSlots(mapData), key);
    }

    public static final Object[] insertAt(
        int initialCapacity, Object[] mapData, int insertionSlot, String key, Object value) {
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

    public static final void removeAt(Object[] mapData, int slot) {
      if (mapData == null || slot < 0) return;

      mapData[slot] = REMOVED;
      mapData[slot + numSlots(mapData)] = null;
    }

    public static final Object getAndRemoveAt(Object[] mapData, int slot) {
      if (mapData == null || slot < 0) return null;

      mapData[slot] = REMOVED;

      int valueIndex = slot + numSlots(mapData);
      Object prev = mapData[valueIndex];
      mapData[valueIndex] = null;

      return prev;
    }

    public static final int findSlot(Object[] mapData, String key) {
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

    public static final Object[] expandMapData(Object[] mapData) {
      // Subtle - capacity is in terms of slots - not array size, so passing length is
      // asking to double capacity
      return expandMapData(mapData, mapData.length);
    }

    public static Object[] expandMapData(Object[] origMapData, int newCapacity) {
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

    public static void forEach(Object[] mapData, BiConsumer<String, Object> entryConsumer) {
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
        Object[] mapData, C ctx, TriConsumer<C, String, Object> entryConsumer) {
      if (mapData == null) return;

      int numSlots = numSlots(mapData);
      for (int slot = 0; slot < numSlots; ++slot) {
        String key = str(mapData[slot]);
        if (key == null || key == REMOVED) continue;

        Object value = mapData[slot + numSlots];
        entryConsumer.accept(ctx, key, value);
      }
    }

    public static String toInternalString(Object[] mapData) {
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
