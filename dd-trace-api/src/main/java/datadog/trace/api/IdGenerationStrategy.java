package datadog.trace.api;

import static java.lang.Long.MAX_VALUE;

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
  protected final boolean traceId128BitGenerationEnabled;

  private IdGenerationStrategy(boolean traceId128BitGenerationEnabled) {
    this.traceId128BitGenerationEnabled = traceId128BitGenerationEnabled;
  }

  public static IdGenerationStrategy fromName(String name) {
    return fromName(name, false);
  }

  public static IdGenerationStrategy fromName(String name, boolean traceId128BitGenerationEnabled) {
    switch (name.toUpperCase()) {
      case "RANDOM":
        return new Random(traceId128BitGenerationEnabled);
      case "SEQUENTIAL":
        return new Sequential(traceId128BitGenerationEnabled);
      case "SECURE_RANDOM":
        return new SRandom(traceId128BitGenerationEnabled);
      default:
        return null;
    }
  }

  public DDTraceId generateTraceId() {
    return this.traceId128BitGenerationEnabled
        ? DD128bTraceId.from(generateHighOrderBits(), getNonZeroPositiveLong())
        : DD64bTraceId.from(getNonZeroPositiveLong());
  }

  public long generateSpanId() {
    return getNonZeroPositiveLong();
  }

  protected abstract long getNonZeroPositiveLong();

  protected long generateHighOrderBits() {
    long timestamp = System.currentTimeMillis() / 1000;
    return timestamp << 32;
  }

  static final class Random extends IdGenerationStrategy {
    private Random(boolean traceId128BitGenerationEnabled) {
      super(traceId128BitGenerationEnabled);
    }

    @Override
    protected long getNonZeroPositiveLong() {
      return ThreadLocalRandom.current().nextLong(0, MAX_VALUE) + 1;
    }
  }

  static final class Sequential extends IdGenerationStrategy {
    private final AtomicLong id;

    private Sequential(boolean traceId128BitGenerationEnabled) {
      super(traceId128BitGenerationEnabled);
      this.id = new AtomicLong(0);
    }

    @Override
    public DDTraceId generateTraceId() {
      // Only use 64-bit TraceId to use incremental values only
      return DD64bTraceId.from(getNonZeroPositiveLong());
    }

    @Override
    protected long getNonZeroPositiveLong() {
      return this.id.incrementAndGet();
    }
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Throwable;
  }

  static final class SRandom extends IdGenerationStrategy {
    private final SecureRandom secureRandom;

    SRandom(boolean traceId128BitGenerationEnabled) {
      this(traceId128BitGenerationEnabled, SecureRandom::getInstanceStrong);
    }

    SRandom(boolean traceId128BitGenerationEnabled, ThrowingSupplier<SecureRandom> supplier) {
      super(traceId128BitGenerationEnabled);
      try {
        secureRandom = supplier.get();
      } catch (Throwable e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    @Override
    protected long getNonZeroPositiveLong() {
      long value = secureRandom.nextLong() & MAX_VALUE;
      while (value == 0) {
        value = secureRandom.nextLong() & MAX_VALUE;
      }
      return value;
    }
  }
}
