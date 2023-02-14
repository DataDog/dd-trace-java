package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.MESSAGE_BROKER;
import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.MESSAGE_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_BROKER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;

public class SqsDecorator extends MessagingClientDecorator {

  public static final CharSequence AWS_HTTP = UTF8BytesString.create("aws.http");
  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");

  public static final CharSequence SQS_RECEIVE = UTF8BytesString.create("Sqs.ReceiveMessage");
  public static final CharSequence SQS_DELIVER = UTF8BytesString.create("Sqs.DeliverMessage");

  public static final boolean SQS_LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "sqs");

  private final String spanKind;
  private final CharSequence spanType;
  private final String serviceName;

  private static final String LOCAL_SERVICE_NAME =
      SQS_LEGACY_TRACING ? "sqs" : Config.get().getServiceName();

  public static final SqsDecorator CONSUMER_DECORATE =
      new SqsDecorator(SPAN_KIND_CONSUMER, MESSAGE_CONSUMER, LOCAL_SERVICE_NAME);

  public static final SqsDecorator BROKER_DECORATE =
      new SqsDecorator(
          SPAN_KIND_BROKER, MESSAGE_BROKER, null /* service name will be set later on */);

  protected SqsDecorator(String spanKind, CharSequence spanType, String serviceName) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceName = serviceName;
  }

  @Override
  protected CharSequence spanType() {
    return spanType;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"aws-sdk"};
  }

  @Override
  protected String service() {
    return serviceName;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onConsume(final AgentSpan span) {
    span.setResourceName(SQS_RECEIVE);
  }

  public void onTimeInQueue(final AgentSpan span) {
    span.setResourceName(SQS_DELIVER);
    span.setServiceName("sqs");
  }
}
