package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

public class DubboHeadersExtractAdapter implements AgentPropagation.ContextVisitor<DubboTraceInfo>{
  private static final Logger log = LoggerFactory.getLogger(DubboHeadersExtractAdapter.class);
  public static final DubboHeadersExtractAdapter GETTER = new DubboHeadersExtractAdapter();
  @Override
  public void forEachKey(DubboTraceInfo carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> objectAttachments = getAttachments(carrier);
    if (log.isDebugEnabled()) {
      log.debug("Dubbo provider Extract size: {}",objectAttachments.entrySet().size());
    }
    for (Map.Entry<String,String> entry : objectAttachments.entrySet()){
      log.debug("Dubbo Extract "+entry.getKey()+":\t\t"+entry.getValue());
      if (null != entry.getValue()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }

  Map<String, String> getAttachments(DubboTraceInfo carrier){
    Map<String, String> objectAttachments = carrier.getContext().getAttachments();
    if (objectAttachments.size()==0) {
      // dubbo version < 2.7.15
      try {
        Map<String, String> attachments = (Map<String, String>) getValue(RpcInvocation.class, carrier.getInvocation(), "attachments");
        return attachments;
      } catch (Exception e) {
        log.error("Dubbo get context attachments exception", e);
      }
    }
    return objectAttachments;
  }

  public static final Object getValue(Class klass, Object instance, String name) throws NoSuchFieldException, IllegalAccessException {
    Field field = klass.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(instance);
  }
}
