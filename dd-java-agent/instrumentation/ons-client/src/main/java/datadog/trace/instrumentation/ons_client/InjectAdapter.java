package datadog.trace.instrumentation.ons_client;

import com.aliyun.openservices.ons.api.Message;
import datadog.context.propagation.CarrierSetter;

public class InjectAdapter implements CarrierSetter<Message>{

  public static final InjectAdapter SETTER = new InjectAdapter();
  
  @Override
  public void set(Message carrier, String key, String value) {
    carrier.putUserProperties(key,value);
  }
}
