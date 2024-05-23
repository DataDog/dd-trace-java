package datadog.trace.instrumentation.hsf;

import com.taobao.hsf.context.RPCContext;
import com.taobao.hsf.invocation.Invocation;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class HSFExtractAdapter implements AgentPropagation.ContextVisitor<RPCContext>{
  private static final Logger log = LoggerFactory.getLogger(HSFExtractAdapter.class);
  public static final HSFExtractAdapter GETTER = new HSFExtractAdapter();
  @Override
  public void forEachKey(RPCContext carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String,Object> objectAttachments = carrier.getAttachments();
    if (log.isDebugEnabled()) {
      log.debug("Extract size: {}",objectAttachments.entrySet().size());
    }
    for (Map.Entry<String,Object> entry : objectAttachments.entrySet()){
      log.debug("Extract "+entry.getKey()+"\t"+entry.getValue());
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey().toString(), entry.getValue().toString())) {
          return;
        }
      }
    }
  }
}
