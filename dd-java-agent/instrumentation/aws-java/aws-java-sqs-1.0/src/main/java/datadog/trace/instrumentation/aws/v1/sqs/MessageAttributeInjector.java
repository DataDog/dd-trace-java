package datadog.trace.instrumentation.aws.v1.sqs;

import datadog.context.propagation.CarrierSetter;

public class MessageAttributeInjector implements CarrierSetter<StringBuilder> {

  public static final MessageAttributeInjector SETTER = new MessageAttributeInjector();

  @Override
  public void set(final StringBuilder builder, final String key, final String value) {
    builder.append('\"').append(key).append("\":\"").append(value).append("\",");
  }
}
