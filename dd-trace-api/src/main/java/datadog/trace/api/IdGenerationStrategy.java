package datadog.trace.api;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public enum IdGenerationStrategy {
  RANDOM {
    @Override
    public DDId generate() {
      return DDId.from(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }

    @Override
    public DDId generateSecure() {
      try {
        SecureRandom secureRandom = SecureRandom.getInstanceStrong();
        return DDId.from(secureRandom.nextLong());
      } catch (NoSuchAlgorithmException e) {
        return generate();
      }
    }
  },
  SEQUENTIAL {
    private final AtomicLong id = new AtomicLong(0);

    @Override
    public DDId generate() {
      return DDId.from(id.incrementAndGet());
    }

    @Override
    public DDId generateSecure() {
      return DDId.from(id.incrementAndGet());
    }
  };

  public abstract DDId generateSecure();

  public abstract DDId generate();
}
