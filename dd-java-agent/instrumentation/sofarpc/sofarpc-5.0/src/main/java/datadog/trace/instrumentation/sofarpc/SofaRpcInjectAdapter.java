package datadog.trace.instrumentation.sofarpc;

import com.alipay.sofa.rpc.core.request.SofaRequest;
import datadog.context.propagation.CarrierSetter;

public final class SofaRpcInjectAdapter implements CarrierSetter<SofaRequest> {

  public static final SofaRpcInjectAdapter SETTER = new SofaRpcInjectAdapter();

  @Override
  public void set(SofaRequest carrier, String key, String value) {
    carrier.addRequestProp(key, value);
  }
}
