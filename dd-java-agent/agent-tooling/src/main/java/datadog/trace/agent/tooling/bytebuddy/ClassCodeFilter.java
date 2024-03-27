package datadog.trace.agent.tooling.bytebuddy;

import java.util.Arrays;

/**
 * Compact filter that records class membership by their hash and short 'class-code'.
 *
 * <p>The 'class-code' includes the length of the package prefix and simple name, as well as the
 * first and last characters of the simple name. These elements coupled with the hash of the full
 * class name should minimize the probability of collisions without needing to store full names,
 * which would otherwise make the filter overly large.
 */
public abstract class ClassCodeFilter {

  private static final int MAX_CAPACITY = 1 << 16;
  private static final int MIN_CAPACITY = 1 << 8;
  private static final int MAX_HASH_ATTEMPTS = 3;

  protected final long[] slots;
  protected final int slotMask;

  protected ClassCodeFilter(int capacity) {
    if (capacity < MIN_CAPACITY) {
      capacity = MIN_CAPACITY;
    } else if (capacity > MAX_CAPACITY) {
      capacity = MAX_CAPACITY;
    }

    // choose enough slot bits to cover the chosen capacity
    slotMask = 0xFFFFFFFF >>> Integer.numberOfLeadingZeros(capacity - 1);
    slots = new long[slotMask + 1];
  }

  public final boolean contains(String name) {
    int hash = name.hashCode();
    for (int i = 1, h = hash; true; i++) {
      long value = slots[slotMask & h];
      if (value == 0) {
        return false;
      } else if ((int) value == hash) {
        return (int) (value >>> 32) == classCode(name);
      } else if (i == MAX_HASH_ATTEMPTS) {
        return false;
      }
      h = rehash(h);
    }
  }

  public final void add(String name) {
    int index;
    int hash = name.hashCode();
    for (int i = 1, h = hash; true; i++) {
      index = slotMask & h;
      if (slots[index] == 0) {
        break;
      } else if (i == MAX_HASH_ATTEMPTS) {
        index = slotMask & hash; // overwrite original slot
        break;
      }
      h = rehash(h);
    }
    slots[index] = (long) classCode(name) << 32 | 0xFFFFFFFFL & hash;
  }

  public final void clear() {
    Arrays.fill(slots, 0);
  }

  /**
   * Computes a 32-bit 'class-code' that includes the length of the package prefix and simple name,
   * plus the first and last characters of the simple name (each truncated to fit into 8-bits.)
   */
  private static int classCode(String name) {
    int start = name.lastIndexOf('.') + 1;
    int end = name.length() - 1;
    int code = 0xFF & start;
    code = (code << 8) | (0xFF & name.charAt(start));
    code = (code << 8) | (0xFF & name.charAt(end));
    return (code << 8) | (0xFF & (end - start));
  }

  private static int rehash(int oldHash) {
    return Integer.reverseBytes(oldHash * 0x9e3775cd) * 0x9e3775cd;
  }
}
