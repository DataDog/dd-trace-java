package datadog.trace.api.naming.v1;

import datadog.trace.api.Config;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.util.Strings;
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
        return SpanNaming.instance().namingSchema().messaging().outboundOperation("sns");
      default:
        final String lowercaseService = cloudService.toLowerCase(Locale.ROOT);
        return Strings.join(".", provider, lowercaseService, "request"); // aws.s3.request
    }
  }

  @Nonnull
  @Override
  public String serviceForRequest(
      @Nonnull final String provider, @Nullable final String cloudService) {
    return Config.get().getServiceName(); // always use DD_SERVICE
  }

  @Nonnull
  @Override
  public String operationForFaas(@Nonnull final String provider) {
    // for now only aws is implemented. For the future provider might be used to return specific
    // function as a service name
    // (e.g. azure automation)
    return "aws.lambda.invoke";
  }
}
