package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class MessagingNamingV1 implements NamingSchema.ForMessaging {

  private String normalizeForCloud(@Nonnull final String messagingSystem) {
    switch (messagingSystem) {
      case "sns":
      case "sqs":
        return "aws." + messagingSystem;
      default:
        return messagingSystem;
    }
  }

  @Nonnull
  @Override
  public String outboundOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".send";
  }

  @Nonnull
  @Override
  public String outboundService(@Nonnull String ddService, @Nonnull String messagingSystem) {
    return ddService;
  }

  @Nonnull
  @Override
  public String inboundOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".process";
  }

  @Nonnull
  @Override
  public String inboundService(@Nonnull String ddService, @Nonnull String messagingSystem) {
    return ddService;
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
