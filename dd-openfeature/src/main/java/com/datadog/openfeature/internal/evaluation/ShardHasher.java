package com.datadog.openfeature.internal.evaluation;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Computes the shard hash {@code int(md5("<salt>-<targetingKey>").hexdigest()[:8], 16)} without
 * allocating.
 *
 * <p>Each thread reuses one instance. {@link #begin} starts an evaluation for one targeting key;
 * within that evaluation each distinct salt is hashed once, however many shards, splits and
 * allocations share it.
 */
final class ShardHasher {
  /** Encoding buffers larger than this are used for one hash and not kept by the thread. */
  private static final int MAX_RETAINED_BYTES = 8 * 1024;

  private static final int INITIAL_CAPACITY = 16;

  private static final ThreadLocal<ShardHasher> PER_THREAD =
      ThreadLocal.withInitial(ShardHasher::new);

  private final MessageDigest md5;
  private final byte[] digest = new byte[16];
  private byte[] saltBuffer = new byte[256];
  private byte[] keyBuffer = new byte[256];

  private String targetingKey;
  private byte[] keyBytes;
  private int keyLength;

  // Salt cache with open addressing. An entry belongs to the current evaluation when its
  // generation matches, so starting an evaluation does not clear the table.
  private String[] salts = new String[INITIAL_CAPACITY];
  private int[] hashes = new int[INITIAL_CAPACITY];
  private int[] generations = new int[INITIAL_CAPACITY];
  private int generation;
  private int size;

  /** The number of digests this instance computed, for tests. */
  long digests;

  ShardHasher() {
    try {
      md5 = MessageDigest.getInstance("MD5");
    } catch (final NoSuchAlgorithmException e) {
      throw new RuntimeException("MD5 algorithm not available", e);
    }
  }

  /** Returns this thread's hasher, ready to hash salts for {@code targetingKey}. */
  static ShardHasher forEvaluation(final String targetingKey) {
    return PER_THREAD.get().begin(targetingKey);
  }

  /** Starts an evaluation for {@code targetingKey}, forgetting the salts of the previous one. */
  ShardHasher begin(final String targetingKey) {
    this.targetingKey = targetingKey;
    keyBytes = null;
    keyLength = -1;
    if (++generation == 0) {
      // The generation wrapped around: clear the table so that no stale entry matches.
      Arrays.fill(generations, 0);
      generation = 1;
    }
    size = 0;
    return this;
  }

  /** Returns the shard of the evaluation's targeting key for {@code salt}. */
  long shard(final String salt, final long totalShards) {
    return (hash(salt) & 0xFFFFFFFFL) % totalShards;
  }

  /** Returns the first four bytes of {@code md5(salt + "-" + targetingKey)}, big-endian. */
  int hash(final String salt) {
    // String concatenation turns a null salt into "null".
    final String key = String.valueOf(salt);
    final int mask = salts.length - 1;
    int slot = mix(key.hashCode()) & mask;
    while (generations[slot] == generation) {
      if (key.equals(salts[slot])) {
        return hashes[slot];
      }
      slot = (slot + 1) & mask;
    }
    final int hash = digest(key);
    salts[slot] = key;
    hashes[slot] = hash;
    generations[slot] = generation;
    if (++size * 2 > salts.length) {
      grow();
    }
    return hash;
  }

  private int digest(final String salt) {
    if (keyLength < 0) {
      keyBytes = ensureCapacity(keyBuffer, String.valueOf(targetingKey));
      keyLength = encode(String.valueOf(targetingKey), keyBytes);
      if (keyBytes.length <= MAX_RETAINED_BYTES) {
        keyBuffer = keyBytes;
      }
    }
    final byte[] saltBytes = ensureCapacity(saltBuffer, salt);
    final int saltLength = encode(salt, saltBytes);
    if (saltBytes.length <= MAX_RETAINED_BYTES) {
      saltBuffer = saltBytes;
    }
    md5.update(saltBytes, 0, saltLength);
    md5.update((byte) '-');
    md5.update(keyBytes, 0, keyLength);
    try {
      md5.digest(digest, 0, digest.length);
    } catch (final DigestException e) {
      throw new IllegalStateException(e);
    }
    digests++;
    return (digest[0] & 0xFF) << 24
        | (digest[1] & 0xFF) << 16
        | (digest[2] & 0xFF) << 8
        | (digest[3] & 0xFF);
  }

  /** Returns {@code buffer} if it can hold {@code value} as UTF-8, or a new array that can. */
  private static byte[] ensureCapacity(final byte[] buffer, final String value) {
    // A char takes at most three UTF-8 bytes; a surrogate pair takes four for two chars.
    final long capacity = value.length() * 3L;
    return buffer.length >= capacity ? buffer : new byte[Math.toIntExact(capacity)];
  }

  /**
   * Encodes {@code value} as UTF-8 into {@code target} and returns the length. Matches {@link
   * String#getBytes} with UTF-8, which replaces an unpaired surrogate with {@code '?'}.
   */
  static int encode(final String value, final byte[] target) {
    final int length = value.length();
    int position = 0;
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
    final int[] oldGenerations = generations;
    final int capacity = oldSalts.length * 2;
    salts = new String[capacity];
    hashes = new int[capacity];
    generations = new int[capacity];
    final int mask = capacity - 1;
    for (int i = 0; i < oldSalts.length; i++) {
      if (oldGenerations[i] == generation) {
        int slot = mix(oldSalts[i].hashCode()) & mask;
        while (generations[slot] == generation) {
          slot = (slot + 1) & mask;
        }
        salts[slot] = oldSalts[i];
        hashes[slot] = oldHashes[i];
        generations[slot] = generation;
      }
    }
  }

  private static int mix(final int hash) {
    return hash ^ (hash >>> 16);
  }
}
