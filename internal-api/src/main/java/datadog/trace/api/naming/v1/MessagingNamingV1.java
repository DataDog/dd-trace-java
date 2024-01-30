package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

public class MessagingNamingV1 implements NamingSchema.ForMessaging {
  private static final Supplier<String> NULL = () -> null;

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
  public Supplier<String> outboundService(
      @Nonnull String messagingSystem, boolean useLegacyTracing) {
    return NULL;
  }

  @Nonnull
  @Override
  public String inboundOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".process";
  }

  @Override
  public Supplier<String> inboundService(
      @Nonnull String messagingSystem, boolean useLegacyTracing) {
    return NULL;
  }

  @Override
  @Nonnull
  public Supplier<String> timeInQueueService(@Nonnull String messagingSystem) {
    return () -> messagingSystem + "-queue";
  }

  @Nonnull
  @Override
  public String timeInQueueOperation(@Nonnull String messagingSystem) {
    return normalizeForCloud(messagingSystem) + ".deliver";
  }
}
