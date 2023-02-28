package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;

public class NamingSchemaV0 implements NamingSchema {
  private final NamingSchema.ForCache cacheNaming = new CacheNamingV0();
  private final NamingSchema.ForClient clientNaming = new ClientNamingV0();
  private final NamingSchema.ForDatabase databaseNaming = new DatabaseNamingV0();
  private final NamingSchema.ForServer serverNaming = new ServerNamingV0();

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

  @Override
  public ForServer server() {
    return serverNaming;
  }
}
