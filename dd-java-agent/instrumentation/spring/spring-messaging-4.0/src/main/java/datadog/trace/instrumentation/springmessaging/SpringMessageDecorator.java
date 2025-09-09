package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes.MESSAGE_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.MessagingClientDecorator;

public final class SpringMessageDecorator extends MessagingClientDecorator {
  public static final SpringMessageDecorator DECORATE = new SpringMessageDecorator();

  public static final CharSequence SPRING_INBOUND =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().messaging().inboundOperation("spring"));

  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("spring-messaging");

  @Override
  protected CharSequence spanType() {
    return MESSAGE_CONSUMER;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-messaging"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String service() {
    return null;
  }

  @Override
  protected String spanKind() {
    return SPAN_KIND_CONSUMER;
  }
}
