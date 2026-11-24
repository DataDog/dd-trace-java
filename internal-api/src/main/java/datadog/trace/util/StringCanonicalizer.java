package datadog.trace.util;

/**
 * Shares one {@code String} instance per distinct text within one scope, typically one parse, so a
 * document that repeats a key, an id or a value thousands of times keeps a single copy of it.
 *
 * <p>Long-lived parsed state, such as a configuration that stays in the old generation until the
 * next one replaces it, pays for each duplicate in memory and in marking work for as long as it
 * lives. Sharing is scoped to an instance on purpose: parsed texts can be customer data, which must
 * not reach a JVM-wide table like {@link String#intern()}'s. Create one instance per parse and drop
 * it when the parse returns.
 *
 * <p>Three bounds keep the cost of a parse predictable, whatever the input:
 *
 * <ul>
 *   <li><b>Length:</b> texts longer than {@code maxLength} are neither hashed nor shared. Long
 *       values rarely repeat, and hashing them is most of the cost.
 *   <li><b>Entries:</b> at most {@code maxEntries} texts are shared. The table holds each one until
 *       the parse ends, including values the parser has already discarded, so without this bound a
 *       document of many distinct short values would grow it without limit. Once full, texts
 *       already shared still are; new ones are returned unshared.
 *   <li><b>Probes:</b> a lookup gives up and returns an unshared copy after comparing {@link
 *       #MAX_EQUAL_HASH_PROBES} strings with its hash code, or after {@link #MAX_PROBES} slots in
 *       all. Strings with equal hash codes are easy to construct, and each one costs a string
 *       comparison, so without the first bound, input made of them would compare every lookup
 *       against all the others. The second bound is far above the longest run ordinary input makes
 *       at half load (under 50 slots, even at a million entries).
 * </ul>
 *
 * <p>Either way, the result is equal to the text, so giving up only costs sharing. The table is
 * open-addressed over parallel arrays of strings and their hashes, spread with {@link
 * HashingUtils#hashSpread}, and grown when half full. Not thread-safe.
 */
public final class StringCanonicalizer {
  /** The default longest text that is shared. */
  public static final int DEFAULT_MAX_LENGTH = 128;

  /**
   * The default most texts that are shared: far above the distinct values of a large flag
   * configuration, while capping the table at {@code 2 * 65536} slots, about 1 MB of arrays.
   */
  public static final int DEFAULT_MAX_ENTRIES = 1 << 16;

  /** The most strings with a lookup's hash code that it compares before giving up. */
  static final int MAX_EQUAL_HASH_PROBES = 16;

  /** The most slots a lookup probes before giving up. */
  static final int MAX_PROBES = 256;

  private static final int INITIAL_CAPACITY = 256;

  private final int maxLength;
  private final int maxEntries;
  private String[] strings = new String[INITIAL_CAPACITY];
  private int[] hashes = new int[INITIAL_CAPACITY];
  private int size;

  /**
   * Creates a canonicalizer that shares up to {@link #DEFAULT_MAX_ENTRIES} texts of up to {@link
   * #DEFAULT_MAX_LENGTH} chars.
   */
  public StringCanonicalizer() {
    this(DEFAULT_MAX_LENGTH, DEFAULT_MAX_ENTRIES);
  }

  /**
   * Creates a canonicalizer that shares up to {@link #DEFAULT_MAX_ENTRIES} texts of up to {@code
   * maxLength} chars.
   *
   * @param maxLength the longest text to share; longer texts are copied but not shared.
   */
  public StringCanonicalizer(final int maxLength) {
    this(maxLength, DEFAULT_MAX_ENTRIES);
  }

  /**
   * Creates a canonicalizer that shares up to {@code maxEntries} texts of up to {@code maxLength}
   * chars.
   *
   * @param maxLength the longest text to share; longer texts are copied but not shared.
   * @param maxEntries the most texts to share; once that many are, new texts are not shared.
   */
  public StringCanonicalizer(final int maxLength, final int maxEntries) {
    if (maxLength < 0) {
      throw new IllegalArgumentException("maxLength < 0: " + maxLength);
    }
    if (maxEntries < 0) {
      throw new IllegalArgumentException("maxEntries < 0: " + maxEntries);
    }
    this.maxLength = maxLength;
    this.maxEntries = maxEntries;
  }

  /**
   * Returns the shared instance for the text in {@code chars[offset, offset + length)}, creating it
   * on the first occurrence. A miss copies the characters, so the caller may reuse the array, as a
   * parser reuses its buffer.
   *
   * @param chars the characters, such as a parser's text buffer.
   * @param offset the index of the first character.
   * @param length the number of characters.
   * @return a {@code String} equal to the text: the shared instance, or an unshared copy when the
   *     text is longer than {@code maxLength} or the lookup gave up.
   * @throws IndexOutOfBoundsException if the range is not within {@code chars}, as {@code new
   *     String(chars, offset, length)} would, whatever the table holds.
   */
  public String canonical(final char[] chars, final int offset, final int length) {
    // A hit never copies the characters, so check the range here rather than leave it to the
    // String constructor on a miss: an empty hit would otherwise accept any offset.
    if ((offset | length) < 0 || length > chars.length - offset) {
      throw new IndexOutOfBoundsException(
          "offset " + offset + ", length " + length + ", array length " + chars.length);
    }
    if (length > maxLength) {
      return new String(chars, offset, length);
    }
    int hash = 0; // String.hashCode's polynomial, so canonical(String) finds the same entries
    for (int i = 0; i < length; i++) {
      hash = 31 * hash + chars[offset + i];
    }
    final int mask = strings.length - 1;
    int slot = HashingUtils.hashSpread(hash) & mask;
    int equalHashes = 0;
    for (int probes = 0; probes < MAX_PROBES; probes++, slot = (slot + 1) & mask) {
      final String existing = strings[slot];
      if (existing == null) {
        return insert(slot, hash, new String(chars, offset, length));
      }
      if (hashes[slot] == hash) {
        if (matches(existing, chars, offset, length)) {
          return existing;
        }
        if (++equalHashes == MAX_EQUAL_HASH_PROBES) {
          break;
        }
      }
    }
    return new String(chars, offset, length);
  }

  /**
   * Returns the shared instance equal to {@code text}, recording {@code text} itself on a miss.
   *
   * @param text the text, as a parser that hands out strings produces it.
   * @return the shared instance, or {@code text} itself when it is longer than {@code maxLength} or
   *     the lookup gave up.
   */
  public String canonical(final String text) {
    if (text.length() > maxLength) {
      return text;
    }
    final int hash = text.hashCode();
    final int mask = strings.length - 1;
    int slot = HashingUtils.hashSpread(hash) & mask;
    int equalHashes = 0;
    for (int probes = 0; probes < MAX_PROBES; probes++, slot = (slot + 1) & mask) {
      final String existing = strings[slot];
      if (existing == null) {
        return insert(slot, hash, text);
      }
      if (hashes[slot] == hash) {
        if (existing.equals(text)) {
          return existing;
        }
        if (++equalHashes == MAX_EQUAL_HASH_PROBES) {
          break;
        }
      }
    }
    return text;
  }

  /**
   * Gets the number of distinct texts shared so far.
   *
   * @return the number of shared instances.
   */
  public int size() {
    return size;
  }

  private String insert(final int slot, final int hash, final String text) {
    if (size == maxEntries) {
      return text; // full: keep sharing what's there, but stop adding
    }
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
        // The new table is a quarter full, so a free slot is normally near. Rehashing still stops
        // where a lookup would, so the insert that triggers growth costs at most MAX_PROBES per
        // entry: an entry with no slot that close is dropped, which only costs sharing it.
        int slot = HashingUtils.hashSpread(oldHashes[i]) & mask;
        int probes = 0;
        while (strings[slot] != null && ++probes < MAX_PROBES) {
          slot = (slot + 1) & mask;
        }
        if (strings[slot] == null) {
          strings[slot] = oldStrings[i];
          hashes[slot] = oldHashes[i];
        } else {
          --size;
        }
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
}
