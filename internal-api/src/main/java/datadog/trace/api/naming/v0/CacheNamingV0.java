package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class CacheNamingV0 implements NamingSchema.ForCache {

  private final boolean allowsFakeServices;

  public CacheNamingV0(boolean allowsFakeServices) {
    this.allowsFakeServices = allowsFakeServices;
  }

  @Nonnull
  @Override
  public String operation(@Nonnull String cacheSystem) {
    String postfix;
    switch (cacheSystem) {
      case "ignite":
        postfix = ".cache";
        break;
      case "hazelcast":
        postfix = ".invoke";
        break;
      default:
        postfix = ".query";
        break;
    }
    return cacheSystem + postfix;
  }

  @Nonnull
  @Override
  public String service(@Nonnull String ddService, @Nonnull String cacheSystem) {
    if (!allowsFakeServices) {
      return ddService;
    }

    if ("hazelcast".equals(cacheSystem)) {
      return "hazelcast-sdk";
    }
    return cacheSystem;
  }
}
