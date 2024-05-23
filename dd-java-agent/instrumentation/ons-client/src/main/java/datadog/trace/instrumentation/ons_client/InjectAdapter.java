package datadog.trace.instrumentation.ons_client;

import com.aliyun.openservices.ons.api.Message;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class InjectAdapter implements AgentPropagation.Setter<Message>{

  public static final InjectAdapter SETTER = new InjectAdapter();
  
  @Override
  public void set(Message carrier, String key, String value) {
    carrier.putUserProperties(key,value);
  }
}
