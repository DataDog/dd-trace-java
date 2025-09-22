package datadog.trace.instrumentation.aws.v2.sqs;

import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.MESSAGE_BROKER;
import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.MESSAGE_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_BROKER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;
import java.util.function.Supplier;

public class SqsDecorator extends MessagingClientDecorator {
  static final CharSequence COMPONENT_NAME = UTF8BytesString.create("java-aws-sdk");
  public static final CharSequence SQS_INBOUND_OPERATION =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation("sqs"));
  public static final CharSequence SQS_RECEIVE = UTF8BytesString.create("Sqs.ReceiveMessage");
  public static final CharSequence SQS_DELIVER = UTF8BytesString.create("Sqs.DeliverMessage");
  public static final CharSequence SQS_TIME_IN_QUEUE_OPERATION =
      SpanNaming.instance().namingSchema().messaging().timeInQueueOperation("sqs");
  public static final boolean SQS_LEGACY_TRACING = Config.get().isSqsLegacyTracingEnabled();

  public static final boolean TIME_IN_QUEUE_ENABLED =
      Config.get().isTimeInQueueEnabled(!SQS_LEGACY_TRACING, "sqs");
  private final String spanKind;
  private final CharSequence spanType;
  private final Supplier<String> serviceNameSupplier;

  public static final SqsDecorator CONSUMER_DECORATE =
      new SqsDecorator(
          SPAN_KIND_CONSUMER,
          MESSAGE_CONSUMER,
          SpanNaming.instance()
              .namingSchema()
              .messaging()
              .inboundService("sqs", SQS_LEGACY_TRACING));

  public static final SqsDecorator BROKER_DECORATE =
      new SqsDecorator(
          SPAN_KIND_BROKER,
          MESSAGE_BROKER,
          SpanNaming.instance().namingSchema().messaging().timeInQueueService("sqs"));

  protected SqsDecorator(
      String spanKind, CharSequence spanType, Supplier<String> serviceNameSupplier) {
    this.spanKind = spanKind;
    this.spanType = spanType;
    this.serviceNameSupplier = serviceNameSupplier;
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
    return serviceNameSupplier.get();
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String spanKind() {
    return spanKind;
  }

  public void onConsume(final AgentSpan span, final String queueUrl, String requestId) {
    span.setResourceName(SQS_RECEIVE);
    span.setTag("aws.service", "Sqs");
    span.setTag("aws_service", "Sqs");
    span.setTag("aws.operation", "ReceiveMessage");
    span.setTag("aws.agent", COMPONENT_NAME);
    span.setTag("aws.queue.url", queueUrl);
    span.setTag("aws.requestId", requestId);
  }

  public void onTimeInQueue(final AgentSpan span, final String queueUrl, String requestId) {
    span.setResourceName(SQS_DELIVER);
    span.setTag("aws.queue.url", queueUrl);
    span.setTag("aws.requestId", requestId);
  }
}
