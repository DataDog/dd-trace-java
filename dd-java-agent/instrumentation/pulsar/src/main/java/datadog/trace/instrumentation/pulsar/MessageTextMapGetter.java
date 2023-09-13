package datadog.trace.instrumentation.pulsar;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;

public class MessageTextMapGetter implements AgentPropagation.ContextVisitor<PulsarRequest>{

  public static final MessageTextMapGetter GETTER = new MessageTextMapGetter();
  @Override
  public void forEachKey(PulsarRequest carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> properties = carrier.getMessage().getProperties();
    for (Map.Entry<String,String> entry : properties.entrySet()){
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
