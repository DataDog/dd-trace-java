package datadog.trace.instrumentation.spark.transport;

import io.openlineage.client.transports.Transport;
import io.openlineage.client.transports.TransportBuilder;
import io.openlineage.client.transports.TransportConfig;

public class DatadogInternalTransportBuilder implements TransportBuilder {
  @Override
  public String getType() {
    return "datadog";
  }

  @Override
  public TransportConfig getConfig() {
    return new DatadogInternalTransportConfig();
  }

  @Override
  public Transport build(TransportConfig config) {
    return new DatadogInternalTransport();
  }
}
