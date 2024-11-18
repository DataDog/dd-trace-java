package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class MessageViewGetter implements AgentPropagation.ContextVisitor<MessageView>{

  private static final Logger log = LoggerFactory.getLogger(MessageViewGetter.class);
  public static final MessageViewGetter GetterView = new MessageViewGetter();

  @Override
  public void forEachKey(MessageView carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> properties = carrier.getProperties();
    if (log.isDebugEnabled()) {
      log.debug("Extract size: {}",properties.entrySet().size());
    }
    for (Map.Entry<String,String> entry : properties.entrySet()){
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
