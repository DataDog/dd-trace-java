package datadog.trace.instrumentation.synapse3;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.synapse.transport.passthru.TargetRequest;

public final class TargetRequestInjectAdapter implements AgentPropagation.Setter<TargetRequest> {
  public static final TargetRequestInjectAdapter SETTER = new TargetRequestInjectAdapter();

  @Override
  public void set(final TargetRequest carrier, final String key, final String value) {
    carrier.addHeader(key, value);
  }
}
