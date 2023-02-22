package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;

public class NamingSchemaV1 implements NamingSchema {
  private final NamingSchema.ForCache cacheNaming = new CacheNamingV1();
  private final NamingSchema.ForClient clientNaming = new ClientNamingV1();

  private final NamingSchema.ForDatabase databaseNaming = new DatabaseNamingV1();

  @Override
  public NamingSchema.ForCache cache() {
    return cacheNaming;
  }

  @Override
  public ForClient client() {
    return clientNaming;
  }

  @Override
  public ForDatabase database() {
    return databaseNaming;
  }
}
