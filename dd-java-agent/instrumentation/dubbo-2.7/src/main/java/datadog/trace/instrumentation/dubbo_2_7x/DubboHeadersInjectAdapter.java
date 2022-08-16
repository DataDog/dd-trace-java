package datadog.trace.instrumentation.dubbo_2_7x;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.dubbo.rpc.Invocation;

public class DubboHeadersInjectAdapter implements AgentPropagation.Setter<Invocation> {
  public static final DubboHeadersInjectAdapter SETTER = new DubboHeadersInjectAdapter();

  @Override
  public void set(Invocation carrier, String key, String value) {
    carrier.setAttachment(key, value);
  }
}
