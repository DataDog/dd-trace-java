package datadog.trace.instrumentation.mqttv5;

import datadog.context.propagation.CarrierSetter;
import java.util.List;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserPropertyInjectAdapter implements CarrierSetter<List<UserProperty>> {
  public static final UserPropertyInjectAdapter SETTER = new UserPropertyInjectAdapter();
  private static final Logger log = LoggerFactory.getLogger(UserPropertyInjectAdapter.class);
  @Override
  public void set(List<UserProperty> carriers, String key, String value) {
    if (log.isDebugEnabled()) {
      log.debug("UserPropertyInjectAdapter Inject {} :\t {}" , key , value);
    }
    carriers.add(new UserProperty(key, value));
  }
}
