package datadog.trace.instrumentation.rocketmq;

import datadog.context.propagation.CarrierSetter;
import org.apache.rocketmq.client.hook.SendMessageContext;

public class TextMapInjectAdapter implements CarrierSetter<SendMessageContext>{

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final SendMessageContext carrier, final String key, final String value) {
    carrier.getMessage().getProperties().put(key,value);
  }
}
