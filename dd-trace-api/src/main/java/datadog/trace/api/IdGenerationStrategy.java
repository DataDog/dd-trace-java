package datadog.trace.api;

import java.security.NoSuchAlgorithmException;
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

  static final class SRandom extends IdGenerationStrategy {
    static DDTraceId secureRandomTraceId;
    static long secureRandomSpanId;

    static {
      try {
        SecureRandom secureRandom = SecureRandom.getInstanceStrong();
        secureRandomTraceId = DDTraceId.from(secureRandom.nextLong());
        secureRandomSpanId = secureRandom.nextLong();
      } catch (NoSuchAlgorithmException e) {
        secureRandomTraceId = null;
        secureRandomSpanId = 0;
      }
    }

    @Override
    public DDTraceId generateTraceId() {
      if (secureRandomTraceId != null) {
        return secureRandomTraceId;
      }
      return DDTraceId.from(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    @Override
    public long generateSpanId() {
      if (secureRandomSpanId != 0) {
        return secureRandomSpanId;
      }
      return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }
  }
}
