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
      default:
        return null;
    }
  }

  public abstract DDTraceId generateTraceId();

  public abstract DDTraceId generateSecureTraceId();

  public abstract long generateSpanId();

  static final class Random extends IdGenerationStrategy {
    @Override
    public DDTraceId generateTraceId() {
      return DDTraceId.from(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    @Override
    public DDTraceId generateSecureTraceId() {
      try {
        SecureRandom secureRandom = SecureRandom.getInstanceStrong();
        return DDTraceId.from(secureRandom.nextLong());
      } catch (NoSuchAlgorithmException e) {
        return generateTraceId();
      }
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
    public DDTraceId generateSecureTraceId() {
      return DDTraceId.from(id.incrementAndGet());
    }

    @Override
    public long generateSpanId() {
      return id.incrementAndGet();
    }
  }
}
