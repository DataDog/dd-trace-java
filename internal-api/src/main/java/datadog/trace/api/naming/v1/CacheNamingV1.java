package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class CacheNamingV1 implements NamingSchema.ForCache {
  @Nonnull
  @Override
  public String operation(@Nonnull String cacheSystem) {
    return cacheSystem + ".command";
  }

  @Override
  public String service(@Nonnull String cacheSystem) {
    return null;
  }
}
