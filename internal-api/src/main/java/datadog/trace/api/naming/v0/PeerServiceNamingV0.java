package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nonnull;

public class PeerServiceNamingV0 implements NamingSchema.ForPeerService {
  @Override
  public boolean supports() {
    return false;
  }

  @Nonnull
  @Override
  public Map<String, Object> tags(@Nonnull final Map<String, Object> unsafeTags) {
    return Collections.emptyMap();
  }
}
