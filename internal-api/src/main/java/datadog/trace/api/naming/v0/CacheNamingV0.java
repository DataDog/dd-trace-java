package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import javax.annotation.Nonnull;

public class CacheNamingV0 implements NamingSchema.ForCache {

  private final boolean allowInferredServices;

  public CacheNamingV0(boolean allowInferredServices) {
    this.allowInferredServices = allowInferredServices;
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

  @Override
  public String service(@Nonnull String cacheSystem) {
    String service = cacheSystem;
    if (!allowInferredServices) {
      return null;
    }
    if ("hazelcast".equals(cacheSystem)) {
      service = "hazelcast-sdk";
    }
    ServiceNameCollector.get().addService(service);
    return service;
  }
}
