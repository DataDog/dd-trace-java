package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class CacheNamingV0 implements NamingSchema.ForCache {
  @Nonnull
  @Override
  public String operation(@Nonnull String cacheSystem) {
    String postfix = ".query";
    if ("ignite".equals(cacheSystem)) {
      postfix = ".cache";
    }
    return cacheSystem + postfix;
  }

  @Nonnull
  @Override
  public String service(@Nonnull String ddService, @Nonnull String cacheSystem) {
    return cacheSystem;
  }
}
