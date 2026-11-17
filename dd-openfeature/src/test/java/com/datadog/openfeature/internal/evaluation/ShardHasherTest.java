package com.datadog.openfeature.internal.evaluation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ShardHasherTest {

  /** The shard algorithm before {@link ShardHasher}: hex digest, first 8 chars, parse. */
  private static long referenceShard(
      final String salt, final String targetingKey, final long totalShards) throws Exception {
    final MessageDigest md = MessageDigest.getInstance("MD5");
    final byte[] hashBytes =
        md.digest((salt + "-" + targetingKey).getBytes(StandardCharsets.UTF_8));
    final StringBuilder hexString = new StringBuilder();
    for (final byte b : hashBytes) {
      final String hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return Long.parseLong(hexString.substring(0, 8), 16) % totalShards;
  }

  /**
   * Expected shards from Python: {@code int(md5(f"{salt}-{key}".encode()).hexdigest()[:8], 16) %
   * n}.
   */
  @TableTest({
    "salt               | targetingKey | totalShards | shard     ",
    "experiment-traffic | alice        | 10000       | 9602      ",
    "experiment-traffic | bob          | 10000       | 540       ",
    "salt               | ''           | 4294967295  | 2189312287",
    "salé               | 日本😀       | 97          | 18        "
  })
  void computesTheShardOfTheHexDigestPrefix(
      final String salt, final String targetingKey, final long totalShards, final long shard)
      throws Exception {
    assertEquals(shard, referenceShard(salt, targetingKey, totalShards));
    assertEquals(shard, new ShardHasher().begin(targetingKey).shard(salt, totalShards));
  }

  @Test
  void matchesTheReferenceAlgorithmForRandomInputs() throws Exception {
    final Random random = new Random(42);
    final ShardHasher hasher = new ShardHasher();
    for (int i = 0; i < 20_000; i++) {
      final String targetingKey = randomString(random);
      hasher.begin(targetingKey);
      for (int j = 0; j < 3; j++) {
        final String salt = randomString(random);
        final long totalShards = randomTotalShards(random);
        assertEquals(
            referenceShard(salt, targetingKey, totalShards),
            hasher.shard(salt, totalShards),
            () -> "salt=" + escape(salt) + " key=" + escape(targetingKey));
      }
    }
  }

  @Test
  void hashesLongInputsLikeTheReferenceAlgorithm() throws Exception {
    final Random random = new Random(7);
    final ShardHasher hasher = new ShardHasher();
    for (final int length : new int[] {255, 256, 3000, 10_000}) {
      final char[] chars = new char[length];
      for (int i = 0; i < length; i++) {
        chars[i] = (char) (0x400 + random.nextInt(0x400));
      }
      final String key = new String(chars);
      final String salt = key.substring(length / 2);
      assertEquals(referenceShard(salt, key, 10_000), hasher.begin(key).shard(salt, 10_000));
    }
  }

  @Test
  void hashesANullSaltAsTheStringNull() throws Exception {
    assertEquals(
        referenceShard(null, "alice", 100), new ShardHasher().begin("alice").shard(null, 100));
  }

  @Test
  void encodesLikeStringGetBytes() {
    final Random random = new Random(3);
    for (int i = 0; i < 20_000; i++) {
      final String value = randomString(random);
      final byte[] target = new byte[value.length() * 3];
      final int length = ShardHasher.encode(value, target);
      assertArrayEquals(
          value.getBytes(StandardCharsets.UTF_8), Arrays.copyOf(target, length), escape(value));
    }
  }

  @Test
  void hashesEachSaltOncePerEvaluation() {
    final ShardHasher hasher = new ShardHasher().begin("alice");
    final long traffic = hasher.shard("experiment-traffic", 10_000);
    for (int split = 0; split < 100; split++) {
      assertEquals(traffic, hasher.shard("experiment-traffic", 10_000));
      hasher.shard("split-" + split, 10_000);
      // A copy of the salt hits the cache too: entries compare by value.
      assertEquals(
          hasher.hash("experiment-traffic"), hasher.hash(new String("experiment-traffic")));
    }
    assertEquals(101, hasher.digests);
  }

  @Test
  void reusesTheCachedHashForEveryTotalShards() throws Exception {
    final ShardHasher hasher = new ShardHasher().begin("alice");
    for (final long totalShards : new long[] {1, 2, 100, 10_000, 4_294_967_295L}) {
      assertEquals(referenceShard("salt", "alice", totalShards), hasher.shard("salt", totalShards));
    }
    assertEquals(1, hasher.digests);
  }

  @Test
  void forgetsTheSaltsOfThePreviousEvaluation() throws Exception {
    final ShardHasher hasher = new ShardHasher();
    for (final String key : new String[] {"alice", "bob", "alice"}) {
      hasher.begin(key);
      for (int salt = 0; salt < 50; salt++) {
        assertEquals(referenceShard("salt-" + salt, key, 997), hasher.shard("salt-" + salt, 997));
      }
    }
    assertEquals(150, hasher.digests);
  }

  @Test
  void reusesOneHasherPerThread() {
    final ShardHasher first = ShardHasher.forEvaluation("alice");
    assertSame(first, ShardHasher.forEvaluation("bob"));
  }

  @Test
  void rejectsZeroTotalShards() {
    final ShardHasher hasher = new ShardHasher().begin("alice");
    assertThrows(ArithmeticException.class, () -> hasher.shard("salt", 0));
  }

  private static long randomTotalShards(final Random random) {
    switch (random.nextInt(4)) {
      case 0:
        return 1 + random.nextInt(100);
      case 1:
        return 10_000;
      case 2:
        return 4_294_967_295L;
      default:
        return 1 + (random.nextLong() >>> 32);
    }
  }

  /** Mixes ASCII, two- and three-byte chars, surrogate pairs and unpaired surrogates. */
  private static String randomString(final Random random) {
    final int length = random.nextInt(40);
    final StringBuilder builder = new StringBuilder(length * 2);
    for (int i = 0; i < length; i++) {
      switch (random.nextInt(8)) {
        case 0:
          builder.append((char) (0x80 + random.nextInt(0x780)));
          break;
        case 1:
          builder.append((char) (0x800 + random.nextInt(0xD000 - 0x800)));
          break;
        case 2:
          builder.appendCodePoint(0x10000 + random.nextInt(0x100000));
          break;
        case 3:
          // An unpaired high or low surrogate.
          builder.append((char) (0xD800 + random.nextInt(0x800)));
          break;
        case 4:
          builder.append((char) (0xE000 + random.nextInt(0x2000)));
          break;
        default:
          builder.append((char) (0x20 + random.nextInt(0x5F)));
      }
    }
    return builder.toString();
  }

  private static String escape(final String value) {
    final StringBuilder builder = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      builder.append(String.format("\\u%04x", (int) value.charAt(i)));
    }
    return builder.toString();
  }
}
