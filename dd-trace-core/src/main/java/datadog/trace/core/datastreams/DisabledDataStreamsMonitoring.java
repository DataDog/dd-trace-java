package datadog.trace.core.datastreams;

import datadog.context.propagation.Propagator;
import datadog.context.propagation.Propagators;
import datadog.trace.api.datastreams.NoopDataStreamsMonitoring;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class DisabledDataStreamsMonitoring extends NoopDataStreamsMonitoring
    implements DataStreamsMonitoring {

  public static final DisabledDataStreamsMonitoring INSTANCE = new DisabledDataStreamsMonitoring();

  private DisabledDataStreamsMonitoring() {}

  @Override
  public void start() {}

  @Override
  public Propagator propagator() {
    return Propagators.noop();
  }

  @Override
  public void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier) {}

  @Override
  public void clear() {}

  @Override
  public void close() {}
}
