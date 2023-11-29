package datadog.trace.api.naming.v1;

import datadog.trace.api.Config;
import datadog.trace.api.naming.NamingSchema;

public class NamingSchemaV1 implements NamingSchema {
  private final NamingSchema.ForCache cacheNaming = new CacheNamingV1();
  private final NamingSchema.ForClient clientNaming = new ClientNamingV1();
  private final NamingSchema.ForCloud cloudNaming = new CloudNamingV1();
  private final NamingSchema.ForDatabase databaseNaming = new DatabaseNamingV1();
  private final NamingSchema.ForMessaging messagingNaming = new MessagingNamingV1();
  private final NamingSchema.ForPeerService peerServiceNaming =
      new PeerServiceNamingV1(Config.get().getPeerServiceComponentOverrides());
  private final NamingSchema.ForServer serverNaming = new ServerNamingV1();

  @Override
  public NamingSchema.ForCache cache() {
    return cacheNaming;
  }

  @Override
  public ForClient client() {
    return clientNaming;
  }

  @Override
  public ForCloud cloud() {
    return cloudNaming;
  }

  @Override
  public ForDatabase database() {
    return databaseNaming;
  }

  @Override
  public ForMessaging messaging() {
    return messagingNaming;
  }

  @Override
  public ForPeerService peerService() {
    return peerServiceNaming;
  }

  @Override
  public boolean allowInferredServices() {
    return false;
  }

  @Override
  public ForServer server() {
    return serverNaming;
  }
}
