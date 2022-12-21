package datadog.trace.instrumentation.rocketmq5;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.rocketmq.client.java.message.MessageImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class MessageMapGetter implements AgentPropagation.ContextVisitor<MessageImpl>{

  private static final Logger log = LoggerFactory.getLogger(MessageMapGetter.class);
  public static final MessageMapGetter GETTER = new MessageMapGetter();

  @Override
  public void forEachKey(MessageImpl carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> properties = carrier.getProperties();
    if (log.isDebugEnabled()) {
      log.debug("Extract size: {}",properties.entrySet().size());
    }
    for (Map.Entry<String,String> entry : properties.entrySet()){
      log.debug("Extract "+entry.getKey()+"\t"+entry.getValue());
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }

  }
}
