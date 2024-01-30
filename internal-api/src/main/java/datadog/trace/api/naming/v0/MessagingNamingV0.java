package datadog.trace.api.naming.v0;

import datadog.trace.api.Config;
import datadog.trace.api.naming.NamingSchema;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public class MessagingNamingV0 implements NamingSchema.ForMessaging {
  private static final Supplier<String> NULL = () -> null;

  private final boolean allowInferredServices;

  public MessagingNamingV0(final boolean allowInferredServices) {
    this.allowInferredServices = allowInferredServices;
  }

  @Nonnull
  @Override
  public String outboundOperation(@Nonnull final String messagingSystem) {
    if ("amqp".equals(messagingSystem)) {
      return "amqp.command";
    }
    return messagingSystem + ".produce";
  }

  @Override
  @Nonnull
  public Supplier<String> outboundService(
      @Nonnull final String messagingSystem, boolean useLegacyTracing) {
    return inboundService(messagingSystem, useLegacyTracing);
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

  @Override
  public Supplier<String> inboundService(
      @Nonnull final String messagingSystem, boolean useLegacyTracing) {
    if (allowInferredServices) {
      return useLegacyTracing ? messagingSystem::toString : Config.get()::getServiceName;
    } else {
      return NULL;
    }
  }

  @Override
  @Nonnull
  public Supplier<String> timeInQueueService(@Nonnull final String messagingSystem) {
    return messagingSystem::toString;
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
