package datadog.trace.instrumentation.dubbo_3_2;

import datadog.context.propagation.CarrierSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DubboHeadersInjectAdapter implements CarrierSetter<DubboMetadata> {
  public static final DubboHeadersInjectAdapter SETTER = new DubboHeadersInjectAdapter();
  private static final Logger log = LoggerFactory.getLogger(DubboHeadersInjectAdapter.class);
  @Override
  public void set(DubboMetadata carrier, String key, String value) {
    if (log.isDebugEnabled()) {
      log.debug("DubboHeadersInjectAdapter dubbo Inject {} :\t {}" , key , value);
    }
    Map<String, Object> objectAttachments;
    if (carrier.getRpcInvocation()==null){
      objectAttachments = carrier.getRequestMetadata().attachments;
    }else{
      objectAttachments = carrier.getRpcInvocation().getObjectAttachments();
    }
    objectAttachments.put(key, value);
  }
}
