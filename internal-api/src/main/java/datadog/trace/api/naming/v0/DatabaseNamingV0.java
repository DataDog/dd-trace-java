package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class DatabaseNamingV0 implements NamingSchema.ForDatabase {
  @Override
  public String normalizedName(@Nonnull String rawName) {
    return rawName;
  }

  @Nonnull
  @Override
  public String operation(@Nonnull String databaseType) {
    String postfix = ".query";
    if ("couchbase".equals(databaseType)) {
      postfix = ".call";
    }
    return databaseType + postfix;
  }

  @Nonnull
  @Override
  public String service(@Nonnull String ddService, @Nonnull String databaseType) {
    return databaseType;
  }
}
