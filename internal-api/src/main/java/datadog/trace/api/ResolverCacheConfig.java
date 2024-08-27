package datadog.trace.api;

/** Named presets to help configure various caches inside the type resolver/matcher. */
public enum ResolverCacheConfig {

  /** Memoizing and outlining for large enterprise apps. */
  LARGE {
    @Override
    public int noMatchesSize() {
      return 65536;
    }

    @Override
    public int visibilitySize() {
      return 4096;
    }

    @Override
    public int memoPoolSize() {
      return 4096;
    }

    @Override
    public int outlinePoolSize() {
      return 256;
    }

    @Override
    public int typePoolSize() {
      return 64;
    }
  },

  /** Memoizing and outlining for the average sized app. */
  MEMOS {
    @Override
    public int noMatchesSize() {
      return 16384;
    }

    @Override
    public int visibilitySize() {
      return 1024;
    }

    @Override
    public int memoPoolSize() {
      return 2048;
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
    public int noMatchesSize() {
      return 0;
    }

    @Override
    public int visibilitySize() {
      return 0;
    }

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
    public int noMatchesSize() {
      return 0;
    }

    @Override
    public int visibilitySize() {
      return 0;
    }

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

  /** No outlining or memoizing. */
  LEGACY {
    @Override
    public int noMatchesSize() {
      return 0;
    }

    @Override
    public int visibilitySize() {
      return 0;
    }

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

  public abstract int noMatchesSize();

  public abstract int visibilitySize();

  public abstract int memoPoolSize();

  public abstract int outlinePoolSize();

  public abstract int typePoolSize();
}
