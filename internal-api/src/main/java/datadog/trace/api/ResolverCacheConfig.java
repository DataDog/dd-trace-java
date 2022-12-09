package datadog.trace.api;

/** Named presets to help configure various caches inside the type resolver/matcher. */
public enum ResolverCacheConfig {

  /** Pool sizes to fit large enterprise apps. */
  LARGE {
    @Override
    public int outlinePoolSize() {
      return 4096;
    }

    @Override
    public int typePoolSize() {
      return 256;
    }
  },

  /** Pool sizes to fit the average sized app. */
  DEFAULT {
    @Override
    public int outlinePoolSize() {
      return 256;
    }

    @Override
    public int typePoolSize() {
      return 32;
    }
  },

  /** Pool sizes to fit small microservice apps. */
  SMALL {
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
    public int outlinePoolSize() {
      return 0;
    }

    @Override
    public int typePoolSize() {
      return 64;
    }
  };

  public abstract int outlinePoolSize();

  public abstract int typePoolSize();
}
