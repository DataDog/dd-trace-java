package com.datadog.openfeature.internal.ufc;

/**
 * Shares one {@code String} instance for each distinct text within one parse, so a parsed
 * configuration keeps a single copy of each attribute name, variation key, allocation key, salt or
 * list value, however many times the document repeats it.
 *
 * <p>A configuration stays in the old generation until the next one replaces it, and every marking
 * cycle walks it, so each duplicate costs memory and marking work for its whole life. Sharing is
 * scoped to one parse on purpose: the texts include customer data, such as {@code ONE_OF} user ids,
 * which must not reach a JVM-wide table like {@link String#intern()}'s.
 *
 * <p>An open-addressing table of strings and their hashes, grown when half full. Not thread-safe:
 * create one per parse and drop it when the parse returns.
 */
final class StringCanonicalizer {
  private static final int INITIAL_CAPACITY = 256;

  private String[] strings = new String[INITIAL_CAPACITY];
  private int[] hashes = new int[INITIAL_CAPACITY];
  private int size;

  /**
   * Returns the shared instance for the text in {@code chars[offset, offset + length)}, creating it
   * on the first occurrence. The characters are copied on a miss, so the caller may reuse the
   * array.
   */
  String canonical(final char[] chars, final int offset, final int length) {
    int hash = 0;
    for (int i = 0; i < length; i++) {
      hash = 31 * hash + chars[offset + i];
    }
    final int mask = strings.length - 1;
    for (int slot = spread(hash) & mask; ; slot = (slot + 1) & mask) {
      final String existing = strings[slot];
      if (existing == null) {
        return insert(slot, hash, new String(chars, offset, length));
      }
      if (hashes[slot] == hash && matches(existing, chars, offset, length)) {
        return existing;
      }
    }
  }

  /** Returns the shared instance equal to {@code text}, recording {@code text} on a miss. */
  String canonical(final String text) {
    final int hash = text.hashCode();
    final int mask = strings.length - 1;
    for (int slot = spread(hash) & mask; ; slot = (slot + 1) & mask) {
      final String existing = strings[slot];
      if (existing == null) {
        return insert(slot, hash, text);
      }
      if (hashes[slot] == hash && existing.equals(text)) {
        return existing;
      }
    }
  }

  /** The number of distinct texts seen so far. */
  int size() {
    return size;
  }

  private String insert(final int slot, final int hash, final String text) {
    strings[slot] = text;
    hashes[slot] = hash;
    if (++size * 2 > strings.length) {
      grow();
    }
    return text;
  }

  private void grow() {
    final String[] oldStrings = strings;
    final int[] oldHashes = hashes;
    strings = new String[oldStrings.length * 2];
    hashes = new int[oldStrings.length * 2];
    final int mask = strings.length - 1;
    for (int i = 0; i < oldStrings.length; i++) {
      if (oldStrings[i] != null) {
        int slot = spread(oldHashes[i]) & mask;
        while (strings[slot] != null) {
          slot = (slot + 1) & mask;
        }
        strings[slot] = oldStrings[i];
        hashes[slot] = oldHashes[i];
      }
    }
  }

  private static boolean matches(
      final String existing, final char[] chars, final int offset, final int length) {
    if (existing.length() != length) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (existing.charAt(i) != chars[offset + i]) {
        return false;
      }
    }
    return true;
  }

  /** Mixes the high bits into the low ones, since the table masks the hash to its low bits. */
  private static int spread(final int hash) {
    return hash ^ (hash >>> 16);
  }
}
