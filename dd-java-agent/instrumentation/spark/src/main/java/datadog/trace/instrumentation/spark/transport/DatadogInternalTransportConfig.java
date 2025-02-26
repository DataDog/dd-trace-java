package datadog.trace.instrumentation.spark.transport;

import io.openlineage.client.transports.TransportConfig;

public class DatadogInternalTransportConfig implements TransportConfig {
  @Override
  public String getName() {
    return "datadog";
  }
}
