package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class ClientNamingV0 implements NamingSchema.ForClient {
  @Nonnull
  @Override
  public String operation(@Nonnull String protocol) {
    return protocol + ".request";
  }
}
