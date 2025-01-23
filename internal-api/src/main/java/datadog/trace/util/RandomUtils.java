package datadog.trace.util;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RandomUtils {
  private RandomUtils() {}

  /** Returns a random UUID. */
  public static UUID randomUUID() {
    Random rnd = ThreadLocalRandom.current();
    long msb = (rnd.nextLong() & 0xffff_ffff_ffff_0fffL) | 0x0000_0000_0000_4000L;
    long lsb = (rnd.nextLong() & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }

  /**
   * Returns a cryptographically strong random UUID.
   *
   * <p>Note on some systems this may have a side effect of initializing java.util.logging, so its
   * use should be avoided during premain.
   */
  @SuppressForbidden
  public static UUID secureRandomUUID() {
    return UUID.randomUUID();
  }
}
