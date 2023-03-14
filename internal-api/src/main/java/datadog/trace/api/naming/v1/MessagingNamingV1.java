package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import javax.annotation.Nonnull;

public class MessagingNamingV1 implements NamingSchema.ForMessaging {

  @Nonnull
  @Override
  public String outboundOperation(@Nonnull String messagingSystem) {
    return messagingSystem + ".send";
  }

  @Nonnull
  @Override
  public String outboundService(@Nonnull String ddService, @Nonnull String messagingSystem) {
    return ddService;
  }

  @Nonnull
  @Override
  public String inboundOperation(@Nonnull String messagingSystem) {
    return messagingSystem + ".process";
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
}
