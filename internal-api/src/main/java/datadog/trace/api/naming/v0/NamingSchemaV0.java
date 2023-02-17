package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;

public class NamingSchemaV0 implements NamingSchema {
  private final NamingSchema.ForCache cacheNaming = new CacheNamingV0();

  @Override
  public NamingSchema.ForCache cache() {
    return cacheNaming;
  }
}
