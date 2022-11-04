package datadog.trace.instrumentation.pubsub;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

public class MessageReceiverDecorator extends ClientDecorator {
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("pubsub");

  public static final MessageReceiverDecorator DECORATE = new MessageReceiverDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"pubsub"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.MESSAGE_CONSUMER;
  }

  @Override
  protected String service() {
    return null;
  }

}
