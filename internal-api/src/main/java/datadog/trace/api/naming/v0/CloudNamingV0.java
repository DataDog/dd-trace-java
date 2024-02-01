package datadog.trace.api.naming.v0;

import static datadog.trace.api.naming.v0.NamingSchemaV0.NULL;

import datadog.trace.api.naming.NamingSchema;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CloudNamingV0 implements NamingSchema.ForCloud {
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
  public Supplier<String> serviceForRequest(
      @Nonnull final String provider, @Nullable final String cloudService) {
    if (!allowInferredServices) {
      return NULL;
    }

    // we only manage aws. Future switch for other cloud providers will be needed in the future
    if (cloudService == null) {
      return "java-aws-sdk"::toString;
    }

    switch (cloudService) {
      case "sns":
      case "sqs":
        return cloudService::toString;
      default:
        return "java-aws-sdk"::toString;
    }
  }

  @Nonnull
  @Override
  public String operationForFaas(@Nonnull final String provider) {
    return "dd-tracer-serverless-span";
  }
}
