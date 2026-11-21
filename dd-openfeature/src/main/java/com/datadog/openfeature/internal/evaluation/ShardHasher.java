package com.datadog.openfeature.internal.evaluation;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Computes the shard hash {@code int(md5("<salt>-<targetingKey>").hexdigest()[:8], 16)} of one
 * evaluation's targeting key, hashing each distinct salt once however many shards, splits and
 * allocations share it.
 *
 * <p>An instance belongs to one evaluation. The digest and the encoding buffer are reused per
 * thread; both are JDK types, so a thread does not keep the application's class loader. After each
 * hash the buffer is zeroed and the digest is reset, which clears the input block it keeps, so a
 * thread does not keep a targeting key either.
 */
final class ShardHasher {
  /** Encoding buffers larger than this are used for one hash and not kept by the thread. */
  private static final int MAX_RETAINED_BYTES = 8 * 1024;

  private static final int INITIAL_CAPACITY = 8;

  private static final ThreadLocal<MessageDigest> MD5 =
      ThreadLocal.withInitial(() -> {
        try {
          return MessageDigest.getInstance("MD5");
        } catch (final NoSuchAlgorithmException e) {
          throw new RuntimeException("MD5 algorithm not available", e);
        }
      });

  private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[256]);

  private final String targetingKey;

  // The first salt needs no table: most flags have a single salt.
  private String firstSalt;
  private int firstHash;

  // Open-addressing table for the other salts, allocated on the second distinct salt.
  private String[] salts;
  private int[] hashes;
  private int size;

  /** The number of digests this instance computed, for tests. */
  int digests;

  /** Starts hashing for {@code targetingKey}, which must not be null. */
  ShardHasher(final String targetingKey) {
    this.targetingKey = targetingKey;
  }

  /** Returns the shard of the targeting key for {@code salt}. */
  long shard(final String salt, final long totalShards) {
    return (hash(salt) & 0xFFFFFFFFL) % totalShards;
  }

  /** Returns the first four bytes of {@code md5(salt + "-" + targetingKey)}, big-endian. */
  int hash(final String salt) {
    // String concatenation turns a null salt into "null".
    final String key = String.valueOf(salt);
    if (firstSalt == null) {
      firstSalt = key;
      return firstHash = digest(key);
    }
    if (key.equals(firstSalt)) {
      return firstHash;
    }
    if (salts == null) {
      salts = new String[INITIAL_CAPACITY];
      hashes = new int[INITIAL_CAPACITY];
    }
    final int mask = salts.length - 1;
    int slot = mix(key.hashCode()) & mask;
    for (String cached; (cached = salts[slot]) != null; slot = (slot + 1) & mask) {
      if (key.equals(cached)) {
        return hashes[slot];
      }
    }
    final int hash = digest(key);
    salts[slot] = key;
    hashes[slot] = hash;
    if (++size * 4 > salts.length * 3) {
      grow();
    }
    return hash;
  }

  private int digest(final String salt) {
    final MessageDigest md5 = MD5.get();
    // A char takes at most three UTF-8 bytes; a surrogate pair takes four for two chars.
    final long capacity = (salt.length() + 1L + targetingKey.length()) * 3;
    byte[] buffer = BUFFER.get();
    if (buffer.length < capacity) {
      buffer = new byte[Math.toIntExact(capacity)];
      if (buffer.length <= MAX_RETAINED_BYTES) {
        BUFFER.set(buffer);
      }
    }
    int length = 0;
    try {
      length = encode(salt, buffer, 0);
      buffer[length++] = '-';
      length = encode(targetingKey, buffer, length);
      md5.update(buffer, 0, length);
      md5.digest(buffer, 0, 16);
      digests++;
      return (buffer[0] & 0xFF) << 24
          | (buffer[1] & 0xFF) << 16
          | (buffer[2] & 0xFF) << 8
          | (buffer[3] & 0xFF);
    } catch (final DigestException e) {
      throw new IllegalStateException(e);
    } finally {
      // Forget the input and its digest. After digest() the MD5 engine still holds the last input
      // block; reset() zeroes it.
      Arrays.fill(buffer, 0, Math.max(length, 16), (byte) 0);
      md5.reset();
    }
  }

  /**
   * Encodes {@code value} as UTF-8 into {@code target} from {@code position} and returns the end
   * position. Matches {@link String#getBytes} with UTF-8, which replaces an unpaired surrogate with
   * {@code '?'}.
   */
  static int encode(final String value, final byte[] target, int position) {
    final int length = value.length();
    for (int i = 0; i < length; i++) {
      final char c = value.charAt(i);
      if (c < 0x80) {
        target[position++] = (byte) c;
      } else if (c < 0x800) {
        target[position++] = (byte) (0xC0 | (c >> 6));
        target[position++] = (byte) (0x80 | (c & 0x3F));
      } else if (Character.isHighSurrogate(c)
          && i + 1 < length
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        final int codePoint = Character.toCodePoint(c, value.charAt(++i));
        target[position++] = (byte) (0xF0 | (codePoint >> 18));
        target[position++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
        target[position++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
        target[position++] = (byte) (0x80 | (codePoint & 0x3F));
      } else if (Character.isSurrogate(c)) {
        target[position++] = '?';
      } else {
        target[position++] = (byte) (0xE0 | (c >> 12));
        target[position++] = (byte) (0x80 | ((c >> 6) & 0x3F));
        target[position++] = (byte) (0x80 | (c & 0x3F));
      }
    }
    return position;
  }

  private void grow() {
    final String[] oldSalts = salts;
    final int[] oldHashes = hashes;
    salts = new String[oldSalts.length * 2];
    hashes = new int[oldSalts.length * 2];
    final int mask = salts.length - 1;
    for (int i = 0; i < oldSalts.length; i++) {
      if (oldSalts[i] != null) {
        int slot = mix(oldSalts[i].hashCode()) & mask;
        while (salts[slot] != null) {
          slot = (slot + 1) & mask;
        }
        salts[slot] = oldSalts[i];
        hashes[slot] = oldHashes[i];
      }
    }
  }

  private static int mix(final int hash) {
    return hash ^ (hash >>> 16);
  }
}
