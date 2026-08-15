package datadog.trace.api.featureflag.ufc.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public class Shard {
  public final String salt;
  public final List<ShardRange> ranges;
  public final int totalShards;

  // The immutable UTF-8 bytes before the targeting key in the shard hash input. Set during
  // configuration preprocessing. The array is private and is never exposed or mutated.
  private transient byte[] saltPrefix;

  public Shard(final String salt, final List<ShardRange> ranges, final int totalShards) {
    this.salt = salt;
    this.ranges = ranges;
    this.totalShards = totalShards;
    cacheSaltPrefix();
  }

  /** Caches the UTF-8 bytes for {@code salt + "-"}. */
  public void cacheSaltPrefix() {
    saltPrefix = (String.valueOf(salt) + "-").getBytes(StandardCharsets.UTF_8);
  }

  /** Adds the cached salt prefix to a digest without exposing the mutable byte array. */
  public void updateDigest(final MessageDigest digest) {
    final byte[] cached = saltPrefix;
    digest.update(
        cached != null ? cached : (String.valueOf(salt) + "-").getBytes(StandardCharsets.UTF_8));
  }
}
