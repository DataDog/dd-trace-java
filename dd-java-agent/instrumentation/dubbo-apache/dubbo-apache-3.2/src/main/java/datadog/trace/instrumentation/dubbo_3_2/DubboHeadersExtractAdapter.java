package datadog.trace.instrumentation.dubbo_3_2;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DubboHeadersExtractAdapter implements AgentPropagation.ContextVisitor<DubboMetadata>{
  private static final Logger log = LoggerFactory.getLogger(DubboHeadersExtractAdapter.class);
  public static final DubboHeadersExtractAdapter GETTER = new DubboHeadersExtractAdapter();
  @Override
  public void forEachKey(DubboMetadata carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, Object> objectAttachments;
    if (carrier.getRpcInvocation()==null){
      objectAttachments = carrier.getRequestMetadata().attachments;
    }else{
      objectAttachments = carrier.getRpcInvocation().getObjectAttachments();
    }
    if (log.isDebugEnabled()) {
      log.debug("Dubbo provider Extract size: {}",objectAttachments.entrySet().size());
    }
    for (Map.Entry<String,Object> entry : objectAttachments.entrySet()){
      log.debug("Dubbo Extract "+entry.getKey()+":\t\t"+entry.getValue());
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue().toString())) {
          return;
        }
      }
    }
  }
}
