package datadog.trace.api.naming.v0;

import datadog.trace.api.Config;
import datadog.trace.api.naming.ClassloaderServiceNames;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import javax.annotation.Nonnull;

class MessagingNamingV0 implements NamingSchema.ForMessaging {
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
  public String outboundService(@Nonnull final String messagingSystem, boolean useLegacyTracing) {
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
  public String inboundService(@Nonnull final String messagingSystem, boolean useLegacyTracing) {
    if (allowInferredServices) {
      if (useLegacyTracing) {
        ServiceNameCollector.get().addService(messagingSystem);
        return messagingSystem;
      } else {
        final String contextual = ClassloaderServiceNames.maybeGetForCurrentThread();
        if (contextual != null) {
          ServiceNameCollector.get().addService(contextual);
          return contextual;
        }
        return Config.get().getServiceName();
      }
    } else {
      return null;
    }
  }

  @Override
  @Nonnull
  public String timeInQueueService(@Nonnull final String messagingSystem) {
    ServiceNameCollector.get().addService(messagingSystem);
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
