package datadog.context.propagation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The carrier keys a propagator reads on extraction: exact names and name prefixes, matched
 * ignoring ASCII case, as propagators compare header names. A carrier visitor that can skip a key
 * cheaply, before decoding its value, uses this to skip keys no propagator reads (see {@link
 * CarrierVisitor#forEachKeyValue(Object, CarrierKeys, java.util.function.BiConsumer)}).
 *
 * <p>{@link #all()} means "any key": a propagator that doesn't declare its keys reads them all, and
 * any union containing it does too. Declaring a smaller set than a propagator reads silently drops
 * the keys left out, so a propagator that can't enumerate them stays on {@code all()}.
 *
 * <p>Immutable.
 */
public final class CarrierKeys {
  private static final CarrierKeys ALL =
      new CarrierKeys(true, new String[0][], new byte[0][][], new String[0]);
  private static final CarrierKeys NONE =
      new CarrierKeys(false, new String[0][], new byte[0][][], new String[0]);

  private final boolean all;

  /** Lower-cased exact names, indexed by length. */
  private final String[][] namesByLength;

  /** Each name's lower-case ASCII bytes (null if it isn't ASCII), parallel to the names. */
  private final byte[][][] asciiByLength;

  /** Lower-cased prefixes. */
  private final String[] prefixes;

  private CarrierKeys(
      boolean all, String[][] namesByLength, byte[][][] asciiByLength, String[] prefixes) {
    this.all = all;
    this.namesByLength = namesByLength;
    this.asciiByLength = asciiByLength;
    this.prefixes = prefixes;
  }

  /** Every key. */
  public static CarrierKeys all() {
    return ALL;
  }

  /** No key. */
  public static CarrierKeys none() {
    return NONE;
  }

  /** Exactly these names. */
  public static CarrierKeys of(String... names) {
    return builder().names(Arrays.asList(names)).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Whether this is {@link #all()}. */
  public boolean isAll() {
    return all;
  }

  /** Whether {@code key} is one of the names, or starts with one of the prefixes, ignoring case. */
  public boolean contains(String key) {
    if (all) {
      return true;
    }
    int length = key.length();
    if (length < namesByLength.length) {
      String[] names = namesByLength[length];
      if (names != null) {
        for (String name : names) {
          if (name.equals(key) || name.regionMatches(true, 0, key, 0, length)) {
            return true;
          }
        }
      }
    }
    for (String prefix : prefixes) {
      if (prefix.length() <= length && key.regionMatches(true, 0, prefix, 0, prefix.length())) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@link #contains(String)} for a key given as the ASCII bytes between {@code key}'s position and
   * limit, without decoding it. Reads only; the buffer's position is left as it is. A key with
   * non-ASCII bytes matches no name or prefix.
   */
  public boolean containsAscii(ByteBuffer key) {
    return all || nameAscii(key) != null || hasPrefixAscii(key);
  }

  /**
   * The declared name (lower-cased) that the ASCII bytes between {@code key}'s position and limit
   * spell, ignoring case, or null if they spell none; prefixes aren't considered. Lets a carrier
   * that matched a key's bytes use the name as the key without decoding it. Reads only.
   */
  public String nameAscii(ByteBuffer key) {
    int length = key.remaining();
    if (length >= namesByLength.length) {
      return null;
    }
    String[] names = namesByLength[length];
    if (names == null) {
      return null;
    }
    int start = key.position();
    if (key.hasArray()) {
      // Keys are almost always lower case already: compare the backing array's bytes exactly
      // first, and only compare ignoring case if the key has an upper-case byte at all
      byte[] bytes = key.array();
      int offset = key.arrayOffset() + start;
      byte[][] asciiNames = asciiByLength[length];
      for (int i = 0; i < names.length; i++) {
        if (bytesEqual(bytes, offset, asciiNames[i], length)) {
          return names[i];
        }
      }
      if (!hasUpperCase(bytes, offset, length)) {
        return null;
      }
    }
    for (String name : names) {
      if (asciiMatches(key, start, name, length)) {
        return name;
      }
    }
    return null;
  }

  /** Whether {@code length} bytes from {@code offset} are {@code expected}'s (null: never). */
  private static boolean bytesEqual(byte[] bytes, int offset, byte[] expected, int length) {
    if (expected == null) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      if (bytes[offset + i] != expected[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasUpperCase(byte[] bytes, int offset, int length) {
    for (int i = offset, end = offset + length; i < end; i++) {
      byte b = bytes[i];
      if (b >= 'A' && b <= 'Z') {
        return true;
      }
    }
    return false;
  }

  /** Whether the ASCII bytes between {@code key}'s position and limit start with a prefix. */
  public boolean hasPrefixAscii(ByteBuffer key) {
    int start = key.position();
    int length = key.remaining();
    for (String prefix : prefixes) {
      if (prefix.length() <= length && asciiMatches(key, start, prefix, prefix.length())) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code length} bytes from {@code start} spell {@code lowerCase}'s first chars. */
  private static boolean asciiMatches(ByteBuffer key, int start, String lowerCase, int length) {
    for (int i = 0; i < length; i++) {
      int b = key.get(start + i);
      if (b >= 'A' && b <= 'Z') {
        b += 'a' - 'A';
      }
      if (b != lowerCase.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /** The keys in this set or in {@code other}. */
  public CarrierKeys union(CarrierKeys other) {
    if (all || other.isNone()) {
      return this;
    }
    if (other.all || isNone()) {
      return other;
    }
    return builder().addAll(this).addAll(other).build();
  }

  private boolean isNone() {
    return !all && namesByLength.length == 0 && prefixes.length == 0;
  }

  @Override
  public String toString() {
    if (all) {
      return "CarrierKeys{all}";
    }
    List<String> names = new ArrayList<>();
    for (String[] sameLength : namesByLength) {
      if (sameLength != null) {
        names.addAll(Arrays.asList(sameLength));
      }
    }
    return "CarrierKeys{names=" + names + ", prefixes=" + Arrays.toString(prefixes) + "}";
  }

  /** Builds a {@link CarrierKeys}. Null and empty names and prefixes are ignored. */
  public static final class Builder {
    private final Set<String> names = new LinkedHashSet<>();
    private final Set<String> prefixes = new LinkedHashSet<>();
    private boolean all;

    private Builder() {}

    public Builder name(String name) {
      if (name != null && !name.isEmpty()) {
        names.add(name.toLowerCase(Locale.ROOT));
      }
      return this;
    }

    public Builder names(Collection<String> names) {
      for (String name : names) {
        name(name);
      }
      return this;
    }

    public Builder prefix(String prefix) {
      if (prefix != null && !prefix.isEmpty()) {
        prefixes.add(prefix.toLowerCase(Locale.ROOT));
      }
      return this;
    }

    /** Adds {@code keys}; adding {@link #all()} makes the result {@code all()}. */
    public Builder addAll(CarrierKeys keys) {
      if (keys.all) {
        all = true;
        return this;
      }
      for (String[] sameLength : keys.namesByLength) {
        if (sameLength != null) {
          names.addAll(Arrays.asList(sameLength));
        }
      }
      prefixes.addAll(Arrays.asList(keys.prefixes));
      return this;
    }

    public CarrierKeys build() {
      if (all) {
        return ALL;
      }
      if (names.isEmpty() && prefixes.isEmpty()) {
        return NONE;
      }
      int maxLength = 0;
      for (String name : names) {
        maxLength = Math.max(maxLength, name.length());
      }
      List<List<String>> grouped = new ArrayList<>();
      for (int i = 0; i <= maxLength; i++) {
        grouped.add(null);
      }
      for (String name : names) {
        List<String> sameLength = grouped.get(name.length());
        if (sameLength == null) {
          sameLength = new ArrayList<>();
          grouped.set(name.length(), sameLength);
        }
        sameLength.add(name);
      }
      String[][] namesByLength = new String[names.isEmpty() ? 0 : maxLength + 1][];
      for (int i = 0; i < namesByLength.length; i++) {
        List<String> sameLength = grouped.get(i);
        if (sameLength != null) {
          namesByLength[i] = sameLength.toArray(new String[0]);
        }
      }
      byte[][][] asciiByLength = new byte[namesByLength.length][][];
      for (int i = 0; i < namesByLength.length; i++) {
        if (namesByLength[i] != null) {
          asciiByLength[i] = new byte[namesByLength[i].length][];
          for (int j = 0; j < namesByLength[i].length; j++) {
            asciiByLength[i][j] = asciiBytes(namesByLength[i][j]);
          }
        }
      }
      return new CarrierKeys(false, namesByLength, asciiByLength, prefixes.toArray(new String[0]));
    }

    /** {@code name}'s ASCII bytes, or null if it has a char outside ASCII. */
    private static byte[] asciiBytes(String name) {
      byte[] bytes = new byte[name.length()];
      for (int i = 0; i < bytes.length; i++) {
        char c = name.charAt(i);
        if (c > 0x7f) {
          return null;
        }
        bytes[i] = (byte) c;
      }
      return bytes;
    }
  }
}
