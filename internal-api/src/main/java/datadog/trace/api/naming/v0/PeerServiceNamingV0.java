package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import java.util.Map;
import javax.annotation.Nonnull;

public class PeerServiceNamingV0 implements NamingSchema.ForPeerService {
  @Override
  public boolean supports() {
    return false;
  }

  @Override
  public void tags(@Nonnull final Map<String, Object> unsafeTags) {}
}
