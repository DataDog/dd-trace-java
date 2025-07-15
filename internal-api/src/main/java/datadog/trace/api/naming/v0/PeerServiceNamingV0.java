package datadog.trace.api.naming.v0;

import datadog.trace.api.TagMap;
import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class PeerServiceNamingV0 implements NamingSchema.ForPeerService {
  @Override
  public boolean supports() {
    return false;
  }

  @Nonnull
  @Override
  public void tags(@Nonnull final TagMap unsafeTags) {}
}
