package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;

public class NamingSchemaV0 implements NamingSchema {
  private final NamingSchema.ForCache cacheNaming = new CacheNamingV0();

  private final NamingSchema.ForDatabase databaseNaming = new DatabaseNamingV0();

  @Override
  public NamingSchema.ForCache cache() {
    return cacheNaming;
  }

  @Override
  public ForDatabase database() {
    return databaseNaming;
  }
}
