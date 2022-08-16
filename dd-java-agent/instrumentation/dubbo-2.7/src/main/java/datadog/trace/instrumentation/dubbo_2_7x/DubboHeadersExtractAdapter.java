package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.dubbo.rpc.Invocation;

import java.util.Map;

public class DubboHeadersExtractAdapter implements AgentPropagation.ContextVisitor<Invocation>{
  public static final DubboHeadersExtractAdapter GETTER = new DubboHeadersExtractAdapter();
  @Override
  public void forEachKey(Invocation carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> objectAttachments = carrier.getAttachments();
    for (Map.Entry<String,String> entry : objectAttachments.entrySet()){
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
