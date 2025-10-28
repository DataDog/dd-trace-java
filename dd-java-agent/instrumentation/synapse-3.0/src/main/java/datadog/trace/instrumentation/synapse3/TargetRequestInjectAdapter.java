package datadog.trace.instrumentation.synapse3;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.synapse.transport.passthru.TargetRequest;

@ParametersAreNonnullByDefault
public final class TargetRequestInjectAdapter implements CarrierSetter<TargetRequest> {
  public static final TargetRequestInjectAdapter SETTER = new TargetRequestInjectAdapter();

  @Override
  public void set(final TargetRequest carrier, final String key, final String value) {
    carrier.addHeader(key, value);
  }
}
