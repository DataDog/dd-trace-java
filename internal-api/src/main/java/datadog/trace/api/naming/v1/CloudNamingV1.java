package datadog.trace.api.naming.v1;

import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CloudNamingV1 implements NamingSchema.ForCloud {
  @Nonnull
  @Override
  public String operationForRequest(
      @Nonnull final String provider,
      @Nonnull final String cloudService,
      @Nonnull final String qualifiedOperation) {
    // only aws sdk is right now implemented
    switch (qualifiedOperation) {
      // sdk 1.x format
      case "SQS.SendMessage":
      case "SQS.SendMessageBatch":
      // sdk 2.x format
      case "Sqs.SendMessage":
      case "Sqs.SendMessageBatch":
        return SpanNaming.instance().namingSchema().messaging().outboundOperation("sqs");

      case "Sqs.ReceiveMessage":
      case "SQS.ReceiveMessage":
        return SpanNaming.instance().namingSchema().messaging().inboundOperation("sqs");
      case "Sns.Publish":
      case "SNS.Publish":
      case "Sns.PublishBatch":
      case "SNS.PublishBatch":
        return SpanNaming.instance().namingSchema().messaging().outboundOperation("sns");
      default:
        final String lowercaseService = cloudService.toLowerCase(Locale.ROOT);
        return String.join(".", provider, lowercaseService, "request"); // aws.s3.request
    }
  }

  @Override
  public String serviceForRequest(
      @Nonnull final String provider, @Nullable final String cloudService) {
    return null;
  }

  @Nonnull
  @Override
  public String operationForFaas(@Nonnull final String provider) {
    switch (provider) {
      case "aws":
        return "aws.lambda.invoke";
      case "azure":
        return "azure.functions.invoke";
      default:
        return "aws.lambda.invoke";
    }
  }
}
