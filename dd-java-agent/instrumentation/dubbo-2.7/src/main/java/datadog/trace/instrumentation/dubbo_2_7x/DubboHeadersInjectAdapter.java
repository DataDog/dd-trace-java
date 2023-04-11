package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class DubboHeadersInjectAdapter implements AgentPropagation.Setter<DubboTraceInfo> {
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
