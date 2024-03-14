package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import javax.annotation.Nonnull;

public class DatabaseNamingV0 implements NamingSchema.ForDatabase {

  private final boolean allowInferredServices;

  public DatabaseNamingV0(boolean allowInferredServices) {
    this.allowInferredServices = allowInferredServices;
  }

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

  @Override
  public String service(@Nonnull String databaseType) {
    if (allowInferredServices) {
      ServiceNameCollector.get().addService(databaseType);
      return databaseType;
    }
    return null;
  }
}
