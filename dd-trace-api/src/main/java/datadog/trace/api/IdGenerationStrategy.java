package datadog.trace.api;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strategy for generating trace ids and span ids.
 *
 * <p>The reason this is not an ENUM is to allow future changes that are crosscutting and
 * configuration based, for example 128 bit trace ids et.c., without changing the public API.
 */
public abstract class IdGenerationStrategy {
  private IdGenerationStrategy() {}

  public static IdGenerationStrategy fromName(String name) {
    switch (name.toUpperCase()) {
      case "RANDOM":
        return new Random();
      case "SEQUENTIAL":
        return new Sequential();
      case "SECURE_RANDOM":
        return new SRandom();
      default:
        return null;
    }
  }

  public abstract DDTraceId generateTraceId();

  public abstract long generateSpanId();

  static final class Random extends IdGenerationStrategy {
    @Override
    public DDTraceId generateTraceId() {
      return DDTraceId.from(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    @Override
    public long generateSpanId() {
      return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
  }

  static final class Sequential extends IdGenerationStrategy {
    private final AtomicLong id = new AtomicLong(0);

    @Override
    public DDTraceId generateTraceId() {
      return DDTraceId.from(id.incrementAndGet());
    }

    @Override
    public long generateSpanId() {
      return id.incrementAndGet();
    }
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }

  static final class SRandom extends IdGenerationStrategy {
    private final SecureRandom secureRandom;

    SRandom() {
      this(SecureRandom::getInstanceStrong);
    }

    SRandom(ThrowingSupplier<SecureRandom> supplier) {
      try {
        secureRandom = supplier.get();
      } catch (Throwable e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    private long getNonZeroPositiveLong() {
      long value = secureRandom.nextLong() & Long.MAX_VALUE;
      while (value == 0) {
        value = secureRandom.nextLong() & Long.MAX_VALUE;
      }
      return value;
    }

    @Override
    public DDTraceId generateTraceId() {
      return DDTraceId.from(getNonZeroPositiveLong());
    }

    @Override
    public long generateSpanId() {
      return getNonZeroPositiveLong();
    }
  }
}
