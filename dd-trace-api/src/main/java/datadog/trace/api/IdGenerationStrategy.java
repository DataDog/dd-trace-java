package datadog.trace.api;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

public enum IdGenerationStrategy {
  RANDOM {
    @Override
    public DDId generate() {
      return DDId.from(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
    }
  },
  SEQUENTIAL {
    private final AtomicLong id = new AtomicLong(0);

    @Override
    public DDId generate() {
      return DDId.from(id.incrementAndGet());
    }
  };

  public abstract DDId generate();
}
