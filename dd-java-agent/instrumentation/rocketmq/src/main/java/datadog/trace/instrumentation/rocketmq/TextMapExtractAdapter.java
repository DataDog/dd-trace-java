package datadog.trace.instrumentation.rocketmq;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.common.message.MessageExt;

import java.util.Map;

public class TextMapExtractAdapter implements AgentPropagation.ContextVisitor<MessageExt>{

  public static final TextMapExtractAdapter GETTER = new TextMapExtractAdapter();

  @Override
  public void forEachKey(MessageExt carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> objectAttachments = carrier.getProperties();
    for (Map.Entry<String,String> entry : objectAttachments.entrySet()){
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
