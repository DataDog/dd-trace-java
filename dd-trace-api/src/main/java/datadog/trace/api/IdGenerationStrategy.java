package datadog.trace.api;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
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
  },
  // DON'T USE THIS IN PRODUCTION - USEFUL FOR DEBUGGING WHICH THREAD IDS WERE CREATED ON
  THREAD_PREFIX {
    private final AtomicInteger id = new AtomicInteger(0);
    private final AtomicInteger prefix = new AtomicInteger(0);
    private final ThreadLocal<Integer> tls =
        new ThreadLocal<Integer>() {
          @Override
          protected Integer initialValue() {
            return prefix.getAndIncrement();
          }
        };

    @Override
    public DDId generate() {
      return DDId.from((tls.get().longValue() << 32) | id.incrementAndGet());
    }
  };

  public abstract DDId generate();
}
