package datadog.trace.util;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RandomUtils {
  private RandomUtils() {}

  public static UUID randomUUID() {
    Random rnd = ThreadLocalRandom.current();
    long msb = (rnd.nextLong() & 0xffff_ffff_ffff_0fffL) | 0x0000_0000_0000_4000L;
    long lsb = (rnd.nextLong() & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb);
  }
}
