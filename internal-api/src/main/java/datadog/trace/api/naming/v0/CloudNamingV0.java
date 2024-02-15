package datadog.trace.api.naming.v0;

import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CloudNamingV0 implements NamingSchema.ForCloud {
  public static final String JAVA_AWS_SDK = "java-aws-sdk";
  private final boolean allowInferredServices;

  public CloudNamingV0(boolean allowInferredServices) {
    this.allowInferredServices = allowInferredServices;
  }

  @Nonnull
  @Override
  public String operationForRequest(
      @Nonnull final String provider,
      @Nonnull final String cloudService,
      @Nonnull final String qualifiedOperation) {
    // only aws sdk is right now implemented
    return "aws.http";
  }

  @Override
  public String serviceForRequest(
      @Nonnull final String provider, @Nullable final String cloudService) {
    if (!allowInferredServices) {
      return null;
    }

    // we only manage aws. Future switch for other cloud providers will be needed in the future
    if (cloudService == null) {
      ServiceNameCollector.get().addService(JAVA_AWS_SDK);
      return JAVA_AWS_SDK;
    }

    switch (cloudService) {
      case "sns":
      case "sqs":
        ServiceNameCollector.get().addService(cloudService);
        return cloudService;
      default:
        ServiceNameCollector.get().addService(JAVA_AWS_SDK);
        return JAVA_AWS_SDK;
    }
  }

  @Nonnull
  @Override
  public String operationForFaas(@Nonnull final String provider) {
    return "dd-tracer-serverless-span";
  }
}
