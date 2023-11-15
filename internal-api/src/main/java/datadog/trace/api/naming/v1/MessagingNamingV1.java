package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class MessagingNamingV1 implements NamingSchema.ForMessaging {

  private String normalizeForCloud(@Nonnull final String messagingSystem) {
    switch (messagingSystem) {
      case "sns":
      case "sqs":
        return "aws." + messagingSystem;
      case "google-pubsub":
        return "gcp.pubsub";
      default:
        return messagingSystem;
    }
  }

  @Nonnull
  @Override
  public String outboundOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".send";
  }

  @Override
  public String outboundService(@Nonnull String messagingSystem, boolean useLegacyTracing) {
    return null;
  }

  @Nonnull
  @Override
  public String inboundOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".process";
  }

  @Override
  public String inboundService(@Nonnull String messagingSystem, boolean useLegacyTracing) {
    return null;
  }

  @Override
  @Nonnull
  public String timeInQueueService(@Nonnull String messagingSystem) {
    return messagingSystem + "-queue";
  }

  @Nonnull
  @Override
  public String timeInQueueOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".deliver";
  }
}
