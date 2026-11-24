package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds which of a fixed set of needles appear as {@code CONSTANT_Utf8} entries in a class file.
 *
 * <p>A type can declare a method only if the method's name is a Utf8 entry in its constant pool. So
 * a needle that is absent rules the method out exactly; a needle that is present only means it may
 * be declared, since the same entry is used by references to methods of that name.
 *
 * <p>A needle ending in {@link ConstantPoolNeedles#PREFIX} is a prefix, present if any Utf8 entry
 * starts with the rest of it.
 *
 * <p>Scanning walks the constant pool once without allocating: entries other than Utf8 are skipped
 * by their tag, and a Utf8 entry is compared only against exact needles with the same byte length
 * and first byte, and against the (few) prefixes. Needles are expected to be ASCII, for which
 * modified UTF-8 is plain ASCII.
 */
public final class ConstantPoolSieve {
  private final Map<String, Integer> ids = new HashMap<>();

  // exact needles have ids [0, needles.length); prefixes follow
  private final byte[][] needles;
  private final int maxLength;
  private final byte[][] prefixes;

  /** byLengthAndFirst[length][firstByte] -> ids of the candidate exact needles, or null. */
  private final int[][][] byLengthAndFirst;

  public ConstantPoolSieve(Collection<String> names) {
    Set<String> exact = new LinkedHashSet<>();
    Set<String> prefixed = new LinkedHashSet<>();
    for (String name : names) {
      if (name.isEmpty() || !isAscii(name)) {
        continue; // can't look for it, so callers treat it as present
      }
      if (name.charAt(name.length() - 1) == ConstantPoolNeedles.PREFIX) {
        if (name.length() > 1) {
          prefixed.add(name); // an empty prefix matches anything, so leave it out
        }
      } else {
        exact.add(name);
      }
    }

    needles = new byte[exact.size()][];
    int max = 0;
    int id = 0;
    for (String name : exact) {
      ids.put(name, id);
      needles[id] = name.getBytes(StandardCharsets.US_ASCII);
      max = Math.max(max, needles[id].length);
      id++;
    }
    maxLength = max;

    prefixes = new byte[prefixed.size()][];
    int p = 0;
    for (String name : prefixed) {
      ids.put(name, needles.length + p);
      prefixes[p++] = name.substring(0, name.length() - 1).getBytes(StandardCharsets.US_ASCII);
    }

    Map<Integer, List<Integer>> buckets = new HashMap<>();
    for (int n = 0; n < needles.length; n++) {
      int key = (needles[n].length << 8) | (needles[n][0] & 0xFF);
      buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(n);
    }
    byLengthAndFirst = new int[maxLength + 1][][];
    for (Map.Entry<Integer, List<Integer>> bucket : buckets.entrySet()) {
      int length = bucket.getKey() >>> 8;
      int first = bucket.getKey() & 0xFF;
      if (null == byLengthAndFirst[length]) {
        byLengthAndFirst[length] = new int[256][];
      }
      List<Integer> candidates = bucket.getValue();
      int[] array = new int[candidates.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = candidates.get(i);
      }
      byLengthAndFirst[length][first] = array;
    }
  }

  /** Number of distinct needles the sieve looks for. */
  public int size() {
    return needles.length + prefixes.length;
  }

  /**
   * Id of the given needle, or -1 if the sieve can't look for it (e.g. it is not ASCII). Callers
   * must treat an id of -1 as "may be present".
   */
  public int id(String needle) {
    Integer id = ids.get(needle);
    return null != id ? id : -1;
  }

  /**
   * Records in {@code present} the ids of the needles found in the class file's constant pool.
   *
   * @return {@code true} if the constant pool was fully scanned; {@code false} if the bytes are not
   *     a well-formed class file, in which case callers must treat every needle as present
   */
  public boolean scan(byte[] bytecode, BitSet present) {
    try {
      if (bytecode.length < 10) {
        return false;
      }
      int count = ((bytecode[8] & 0xFF) << 8) | (bytecode[9] & 0xFF);
      int offset = 10;
      for (int index = 1; index < count; index++) {
        int tag = bytecode[offset];
        switch (tag) {
          case 1: // Utf8
            int length = ((bytecode[offset + 1] & 0xFF) << 8) | (bytecode[offset + 2] & 0xFF);
            int start = offset + 3;
            if (length > 0) {
              if (length <= maxLength) {
                int[][] byFirst = byLengthAndFirst[length];
                if (null != byFirst) {
                  int[] candidates = byFirst[bytecode[start] & 0xFF];
                  if (null != candidates) {
                    for (int id : candidates) {
                      if (regionMatches(bytecode, start, needles[id])) {
                        present.set(id);
                      }
                    }
                  }
                }
              }
              for (int p = 0; p < prefixes.length; p++) {
                byte[] prefix = prefixes[p];
                if (prefix.length <= length && regionMatches(bytecode, start, prefix)) {
                  present.set(needles.length + p);
                }
              }
            }
            offset = start + length;
            break;
          case 3: // Integer
          case 4: // Float
          case 9: // Fieldref
          case 10: // Methodref
          case 11: // InterfaceMethodref
          case 12: // NameAndType
          case 17: // Dynamic
          case 18: // InvokeDynamic
            offset += 5;
            break;
          case 5: // Long
          case 6: // Double
            offset += 9;
            index++; // takes two constant pool slots
            break;
          case 7: // Class
          case 8: // String
          case 16: // MethodType
          case 19: // Module
          case 20: // Package
            offset += 3;
            break;
          case 15: // MethodHandle
            offset += 4;
            break;
          default:
            return false; // unknown tag: can't walk any further
        }
      }
      return true;
    } catch (ArrayIndexOutOfBoundsException e) {
      return false; // truncated class file
    }
  }

  private static boolean regionMatches(byte[] bytecode, int start, byte[] needle) {
    for (int i = 0; i < needle.length; i++) {
      if (bytecode[start + i] != needle[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAscii(String name) {
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == 0 || c > 0x7F) {
        return false; // modified UTF-8 encodes these differently
      }
    }
    return true;
  }
}
