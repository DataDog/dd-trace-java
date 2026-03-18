package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.context.propagation.CarrierSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DubboHeadersInjectAdapter implements CarrierSetter<DubboTraceInfo> {
  public static final DubboHeadersInjectAdapter SETTER = new DubboHeadersInjectAdapter();
  private static final Logger log = LoggerFactory.getLogger(DubboHeadersInjectAdapter.class);
  @Override
  public void set(DubboTraceInfo carrier, String key, String value) {
    if (log.isDebugEnabled()) {
      log.debug("dubbo Inject {} :\t {}" , key , value);
    }
    carrier.getContext().setAttachment(key, value);
    carrier.getInvocation().setAttachment(key, value);
  }
}
