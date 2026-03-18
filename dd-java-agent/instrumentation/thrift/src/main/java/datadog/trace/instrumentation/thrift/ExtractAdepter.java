package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

import java.util.Map;

public class ExtractAdepter  implements AgentPropagation.ContextVisitor<Map<String, String>> {
  public static final ExtractAdepter GETTER = new ExtractAdepter();

  @Override
  public void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
    for (Map.Entry<String,String> entry : carrier.entrySet()){
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
