package datadog.trace.instrumentation.sofarpc;

import com.alipay.sofa.rpc.core.request.SofaRequest;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;

public final class SofaRpcExtractAdapter implements AgentPropagation.ContextVisitor<SofaRequest> {

  public static final SofaRpcExtractAdapter GETTER = new SofaRpcExtractAdapter();

  @Override
  public void forEachKey(SofaRequest carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, Object> props = carrier.getRequestProps();
    if (props == null || props.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : props.entrySet()) {
      if (entry.getValue() instanceof String) {
        if (!classifier.accept(entry.getKey(), (String) entry.getValue())) {
          return;
        }
      }
    }
  }
}
