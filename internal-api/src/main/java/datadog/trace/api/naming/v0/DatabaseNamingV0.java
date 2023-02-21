package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class DatabaseNamingV0 implements NamingSchema.ForDatabase {
  @Nonnull
  @Override
  public String operation(@Nonnull String databaseType) {
    return databaseType + ".query";
  }

  @Nonnull
  @Override
  public String service(@Nonnull String ddService, @Nonnull String databaseType) {
    return databaseType;
  }
}
