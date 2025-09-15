package datadog.trace.instrumentation.rocketmq5;

import datadog.context.propagation.CarrierSetter;
import org.apache.rocketmq.client.java.message.MessageBuilderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageMapSetter implements CarrierSetter<MessageBuilderImpl>{
  public static final MessageMapSetter SETTER = new MessageMapSetter();
  private static final Logger log = LoggerFactory.getLogger(MessageMapSetter.class);
  @Override
  public void set(MessageBuilderImpl carrier, String key, String value) {
    if (log.isDebugEnabled()) {
      log.debug("rocketmq Inject {} :\t {}" , key , value);
    }
    carrier.addProperty(key,value);
  }
}
