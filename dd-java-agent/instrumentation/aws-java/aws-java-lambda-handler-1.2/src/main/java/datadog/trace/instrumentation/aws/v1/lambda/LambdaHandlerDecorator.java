package datadog.trace.instrumentation.aws.v1.lambda;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;

public class LambdaHandlerDecorator {
  public static final UTF8BytesString INVOCATION_SPAN_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cloud().operationForFaas("aws"));
}
