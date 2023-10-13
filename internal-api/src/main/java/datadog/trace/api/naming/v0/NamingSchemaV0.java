package datadog.trace.api.naming.v0;

import datadog.trace.api.Config;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.v1.PeerServiceNamingV1;

public class NamingSchemaV0 implements NamingSchema {

  private final boolean allowInferredServices =
      !Config.get().isRemoveIntegrationServiceNamesEnabled();
  private final NamingSchema.ForCache cacheNaming = new CacheNamingV0(allowInferredServices);
  private final NamingSchema.ForClient clientNaming = new ClientNamingV0();
  private final NamingSchema.ForCloud cloudNaming = new CloudNamingV0(allowInferredServices);
  private final NamingSchema.ForDatabase databaseNaming =
      new DatabaseNamingV0(allowInferredServices);
  private final NamingSchema.ForMessaging messagingNaming =
      new MessagingNamingV0(allowInferredServices);
  private final NamingSchema.ForPeerService peerServiceNaming =
      Config.get().isPeerServiceDefaultsEnabled()
          ? new PeerServiceNamingV1(Config.get().getPeerServiceComponentOverrides())
          : new PeerServiceNamingV0();
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
  public ForServer server() {
    return serverNaming;
  }

  @Override
  public ForPeerService peerService() {
    return peerServiceNaming;
  }

  @Override
  public boolean allowInferredServices() {
    return allowInferredServices;
  }
}
