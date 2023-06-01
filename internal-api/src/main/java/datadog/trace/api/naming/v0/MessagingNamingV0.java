package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class MessagingNamingV0 implements NamingSchema.ForMessaging {
  private final boolean allowsFakeServices;

  public MessagingNamingV0(boolean allowsFakeServices) {
    this.allowsFakeServices = allowsFakeServices;
  }

  @Nonnull
  @Override
  public String outboundOperation(@Nonnull final String messagingSystem) {
    if ("amqp".equals(messagingSystem)) {
      return "amqp.command";
    }
    return messagingSystem + ".produce";
  }

  @Nonnull
  @Override
  public String outboundService(
      @Nonnull final String ddService, @Nonnull final String messagingSystem) {
    if (allowsFakeServices) {
      return messagingSystem;
    }
    return ddService;
  }

  @Nonnull
  @Override
  public String inboundOperation(@Nonnull final String messagingSystem) {
    switch (messagingSystem) {
      case "amqp":
        return "amqp.command";
      case "sqs":
        return "aws.http";
      default:
        return messagingSystem + ".consume";
    }
  }

  @Nonnull
  @Override
  public String inboundService(
      @Nonnull final String ddService, @Nonnull final String messagingSystem) {
    if (allowsFakeServices) {
      return messagingSystem;
    }
    return ddService;
  }

  @Override
  @Nonnull
  public String timeInQueueService(@Nonnull final String messagingSystem) {
    return messagingSystem;
  }

  @Nonnull
  @Override
  public String timeInQueueOperation(@Nonnull String messagingSystem) {
    if ("sqs".equals(messagingSystem)) {
      return "aws.http";
    }
    return messagingSystem + ".deliver";
  }
}
