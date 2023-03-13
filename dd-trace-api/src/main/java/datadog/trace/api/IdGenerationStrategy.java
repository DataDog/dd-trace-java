package datadog.trace.api;

import static datadog.trace.api.IdGenerationStrategy.Trace128bitStrategy.UNSUPPORTED;

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
  protected final Trace128bitStrategy trace128bitStrategy;

  private IdGenerationStrategy(Trace128bitStrategy trace128bitStrategy) {
    this.trace128bitStrategy = trace128bitStrategy;
  }

  public static IdGenerationStrategy fromName(String name) {
    return fromName(name, UNSUPPORTED);
  }

  public static IdGenerationStrategy fromName(
      String name, Trace128bitStrategy trace128bitStrategy) {
    switch (name.toUpperCase()) {
      case "RANDOM":
        return new Random(trace128bitStrategy);
      case "SEQUENTIAL":
        return new Sequential(trace128bitStrategy);
      case "SECURE_RANDOM":
        return new SRandom(trace128bitStrategy);
      default:
        return null;
    }
  }

  public abstract DDTraceId generateTraceId();

  public abstract long generateSpanId();

  protected long generateHighOrderBits() {
    long timestamp = System.currentTimeMillis() / 1000;
    return timestamp << 32;
  }

  public enum Trace128bitStrategy {
    GENERATION,
    GENERATION_AND_LOG_INJECTION,
    UNSUPPORTED;

    public static Trace128bitStrategy get(boolean withGeneration, boolean withLogInjection) {
      if (!withGeneration) {
        return UNSUPPORTED;
      }
      return withLogInjection ? GENERATION_AND_LOG_INJECTION : GENERATION;
    }
  }

  static final class Random extends IdGenerationStrategy {
    private Random(Trace128bitStrategy trace128bitStrategy) {
      super(trace128bitStrategy);
    }

    @Override
    public DDTraceId generateTraceId() {
      return this.trace128bitStrategy != UNSUPPORTED
          ? DD128bTraceId.from(generateHighOrderBits(), ThreadLocalRandom.current().nextLong())
          : DD64bTraceId.from(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    @Override
    public long generateSpanId() {
      return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
  }

  static final class Sequential extends IdGenerationStrategy {
    private final AtomicLong id;

    private Sequential(Trace128bitStrategy trace128bitStrategy) {
      super(trace128bitStrategy);
      this.id = new AtomicLong(0);
    }

    @Override
    public DDTraceId generateTraceId() {
      return DD64bTraceId.from(id.incrementAndGet());
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

    SRandom(Trace128bitStrategy trace128bitStrategy) {
      this(trace128bitStrategy, SecureRandom::getInstanceStrong);
    }

    SRandom(Trace128bitStrategy trace128bitStrategy, ThrowingSupplier<SecureRandom> supplier) {
      super(trace128bitStrategy);
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
      return this.trace128bitStrategy != UNSUPPORTED
          ? DD128bTraceId.from(generateHighOrderBits(), secureRandom.nextLong())
          : DD64bTraceId.from(getNonZeroPositiveLong());
    }

    @Override
    public long generateSpanId() {
      return getNonZeroPositiveLong();
    }
  }
}
