package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class DatabaseNamingV1 implements NamingSchema.ForDatabase {
  @Nonnull
  private String normalizeDatabaseType(@Nonnull String databaseType) {
    // there will more entries (e.g. postgres,..) since the name we use is not always
    // the one chosen for v1 naming conventions
    switch (databaseType) {
      case "mongo":
        return "mongodb";
      case "elasticsearch.rest":
        return "elasticsearch";
    }
    return databaseType;
  }

  @Nonnull
  @Override
  public String operation(@Nonnull String databaseType) {
    return normalizeDatabaseType(databaseType) + ".query";
  }

  @Nonnull
  @Override
  public String service(@Nonnull String ddService, @Nonnull String databaseType) {
    return ddService + "-" + normalizeDatabaseType(databaseType);
  }
}
