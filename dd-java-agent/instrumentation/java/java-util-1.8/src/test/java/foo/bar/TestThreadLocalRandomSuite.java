package foo.bar;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestThreadLocalRandomSuite {

  private final Logger LOGGER = LoggerFactory.getLogger(TestThreadLocalRandomSuite.class);

  private final ThreadLocalRandom random;

  public TestThreadLocalRandomSuite(final ThreadLocalRandom random) {
    this.random = random;
  }

  public boolean nextBoolean() {
    LOGGER.debug("Before nextBoolean");
    final boolean result = random.nextBoolean();
    LOGGER.debug("After nextBoolean {}", result);
    return result;
  }

  public int nextInt() {
    LOGGER.debug("Before nextInt");
    final int result = random.nextInt();
    LOGGER.debug("After nextInt {}", result);
    return result;
  }

  public int nextInt(final int i) {
    LOGGER.debug("Before nextInt {}", i);
    final int result = random.nextInt(i);
    LOGGER.debug("After nextInt {}", result);
    return result;
  }

  public long nextLong() {
    LOGGER.debug("Before nextLong");
    final long result = random.nextLong();
    LOGGER.debug("After nextLong {}", result);
    return result;
  }

  public float nextFloat() {
    LOGGER.debug("Before nextFloat");
    final float result = random.nextFloat();
    LOGGER.debug("After nextFloat {}", result);
    return result;
  }

  public double nextDouble() {
    LOGGER.debug("Before nextDouble");
    final double result = random.nextDouble();
    LOGGER.debug("After nextDouble {}", result);
    return result;
  }

  public double nextGaussian() {
    LOGGER.debug("Before nextGaussian");
    final double result = random.nextGaussian();
    LOGGER.debug("After nextDouble {}", result);
    return result;
  }

  public byte[] nextBytes(final byte[] bytes) {
    LOGGER.debug("Before nextBytes {}", bytes);
    random.nextBytes(bytes);
    LOGGER.debug("After nextBytes");
    return bytes;
  }

  public IntStream ints() {
    LOGGER.debug("Before ints");
    final IntStream result = random.ints();
    LOGGER.debug("After ints {}", result);
    return result;
  }

  public IntStream ints(final int origin, final int bound) {
    LOGGER.debug("Before ints {}, {}", origin, bound);
    final IntStream result = random.ints(origin, bound);
    LOGGER.debug("After ints {}", result);
    return result;
  }

  public IntStream ints(final long size) {
    LOGGER.debug("Before ints {}", size);
    final IntStream result = random.ints(size);
    LOGGER.debug("After ints {}", result);
    return result;
  }

  public IntStream ints(final long size, final int origin, final int bound) {
    LOGGER.debug("Before ints {}, {}, {}", size, origin, bound);
    final IntStream result = random.ints(size, origin, bound);
    LOGGER.debug("After ints {}", result);
    return result;
  }

  public DoubleStream doubles() {
    LOGGER.debug("Before doubles");
    final DoubleStream result = random.doubles();
    LOGGER.debug("After doubles {}", result);
    return result;
  }

  public DoubleStream doubles(final double origin, final double bound) {
    LOGGER.debug("Before doubles {}, {}", origin, bound);
    final DoubleStream result = random.doubles(origin, bound);
    LOGGER.debug("After doubles {}", result);
    return result;
  }

  public DoubleStream doubles(final long size) {
    LOGGER.debug("Before doubles {}", size);
    final DoubleStream result = random.doubles(size);
    LOGGER.debug("After doubles {}", result);
    return result;
  }

  public DoubleStream doubles(final long size, final double origin, final double bound) {
    LOGGER.debug("Before doubles {}, {}, {}", size, origin, bound);
    final DoubleStream result = random.doubles(size, origin, bound);
    LOGGER.debug("After doubles {}", result);
    return result;
  }

  public LongStream longs() {
    LOGGER.debug("Before longs");
    final LongStream result = random.longs();
    LOGGER.debug("After longs {}", result);
    return result;
  }

  public LongStream longs(final long origin, final long bound) {
    LOGGER.debug("Before longs {}, {}", origin, bound);
    final LongStream result = random.longs(origin, bound);
    LOGGER.debug("After longs {}", result);
    return result;
  }

  public LongStream longs(final long size) {
    LOGGER.debug("Before longs {}", size);
    final LongStream result = random.longs(size);
    LOGGER.debug("After longs {}", result);
    return result;
  }

  public LongStream longs(final long size, final long origin, final long bound) {
    LOGGER.debug("Before longs {}, {}, {}", size, origin, bound);
    final LongStream result = random.longs(size, origin, bound);
    LOGGER.debug("After longs {}", result);
    return result;
  }
}
