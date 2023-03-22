package datadog.trace.api;

/** Named presets to help configure various caches inside the type resolver/matcher. */
public enum ResolverCacheConfig {

  /** Memoizing and outlining for large enterprise apps. */
  LARGE {
    @Override
    public int memoPoolSize() {
      return 32768;
    }

    @Override
    public int outlinePoolSize() {
      return 512;
    }

    @Override
    public int typePoolSize() {
      return 128;
    }
  },

  /** Memoizing and outlining for the average sized app. */
  MEMOS {
    @Override
    public int memoPoolSize() {
      return 8192;
    }

    @Override
    public int outlinePoolSize() {
      return 128;
    }

    @Override
    public int typePoolSize() {
      return 32;
    }
  },

  /** Outlining only for the average sized app, no memoizing. */
  NO_MEMOS {
    @Override
    public int memoPoolSize() {
      return 0;
    }

    @Override
    public int outlinePoolSize() {
      return 256;
    }

    @Override
    public int typePoolSize() {
      return 32;
    }
  },

  /** Outlining only for small microservice apps. */
  SMALL {
    @Override
    public int memoPoolSize() {
      return 0;
    }

    @Override
    public int outlinePoolSize() {
      return 32;
    }

    @Override
    public int typePoolSize() {
      return 16;
    }
  },

  /** The old {@code DDCachingPoolStrategy} behaviour. */
  LEGACY {
    @Override
    public int memoPoolSize() {
      return 0;
    }

    @Override
    public int outlinePoolSize() {
      return 0;
    }

    @Override
    public int typePoolSize() {
      return 64;
    }
  };

  public abstract int memoPoolSize();

  public abstract int outlinePoolSize();

  public abstract int typePoolSize();
}
