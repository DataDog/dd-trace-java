package datadog.trace.instrumentation.spark.transport;

import datadog.trace.instrumentation.spark.AbstractDatadogSparkListener;
import io.openlineage.client.OpenLineage;
import io.openlineage.client.transports.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatadogInternalTransport extends Transport {
  protected static final Logger log = LoggerFactory.getLogger(AbstractDatadogSparkListener.class);

  public DatadogInternalTransport() {}

  @Override
  public void emit(OpenLineage.RunEvent runEvent) {
    EventAccumulator.addRunEvent(runEvent);
    log.error("Emitting run event: {}", runEvent);
  }

  @Override
  public void emit(OpenLineage.DatasetEvent datasetEvent) {
    EventAccumulator.addDatasetEvent(datasetEvent);
    log.error("Emitting dataset event: {}", datasetEvent);
  }

  @Override
  public void emit(OpenLineage.JobEvent jobEvent) {
    EventAccumulator.addJobEvent(jobEvent);
    log.error("Emitting job event: {}", jobEvent);
  }
}
