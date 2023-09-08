package datadog.trace.instrumentation.ons_client;

import com.aliyun.openservices.ons.api.Message;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

import java.util.Map;
import java.util.Properties;

public class ExtractAdapter implements AgentPropagation.ContextVisitor<Message>{
  public static final ExtractAdapter GETTER = new ExtractAdapter();
  @Override
  public void forEachKey(Message carrier, AgentPropagation.KeyClassifier classifier) {
    Properties properties = carrier.getUserProperties();
    for (Map.Entry<Object,Object> entry : properties.entrySet()){
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey().toString(), entry.getValue().toString())) {
          return;
        }
      }
    }

  }
}
