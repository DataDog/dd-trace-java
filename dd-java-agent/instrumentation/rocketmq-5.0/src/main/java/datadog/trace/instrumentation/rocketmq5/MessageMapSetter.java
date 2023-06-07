package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.rocketmq.client.java.message.MessageBuilderImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageMapSetter implements AgentPropagation.Setter<MessageBuilderImpl>{
  public static final MessageMapSetter SETTER = new MessageMapSetter();
  private static final Logger log = LoggerFactory.getLogger(MessageMapSetter.class);
  @Override
  public void set(MessageBuilderImpl carrier, String key, String value) {
    if (log.isDebugEnabled()) {
//      System.out.println("dubbo Inject " + key + ":\t" + value);
      log.debug("dubbo Inject {} :\t {}" , key , value);
    }
    carrier.addProperty(key,value);
  }
}
