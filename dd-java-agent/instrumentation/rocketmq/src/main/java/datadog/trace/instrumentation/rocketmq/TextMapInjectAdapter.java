package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.rocketmq.client.hook.SendMessageContext;

import java.util.Map;

public class TextMapInjectAdapter implements AgentPropagation.Setter<SendMessageContext>{

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final SendMessageContext carrier, final String key, final String value) {
    carrier.getMessage().getProperties().put(key,value);
  }
}
