package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.dubbo.rpc.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DubboHeadersExtractAdapter implements AgentPropagation.ContextVisitor<Invocation>{
  private static final Logger log = LoggerFactory.getLogger(DubboHeadersExtractAdapter.class);
  public static final DubboHeadersExtractAdapter GETTER = new DubboHeadersExtractAdapter();
  @Override
  public void forEachKey(Invocation carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> objectAttachments = carrier.getAttachments();
    if (log.isDebugEnabled()) {
      log.debug("Extract size: {}",objectAttachments.entrySet().size());
    }
    for (Map.Entry<String,String> entry : objectAttachments.entrySet()){
      log.debug("Extract "+entry.getKey()+"\t"+entry.getValue());
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
