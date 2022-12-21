package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MessageViewSetter implements AgentPropagation.Setter<MessageView>{
  public static final MessageViewSetter setterView = new MessageViewSetter();
  private static final Logger log = LoggerFactory.getLogger(MessageViewSetter.class);
  @Override
  public void set(MessageView carrier, String key, String value) {
    if (log.isDebugEnabled()) {
//      System.out.println("dubbo Inject " + key + ":\t" + value);
      log.debug("dubbo Inject {} :\t {}" , key , value);
    }
    carrier.getProperties().put(key,value);
  }
}
