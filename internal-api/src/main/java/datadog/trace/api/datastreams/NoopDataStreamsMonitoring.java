package datadog.trace.api.datastreams;

import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;

public class NoopDataStreamsMonitoring implements AgentDataStreamsMonitoring {
  public static final NoopDataStreamsMonitoring INSTANCE = new NoopDataStreamsMonitoring();

  @Override
  public void trackBacklog(DataStreamsTags tags, long value) {}

  @Override
  public void setSchemaRegistryUsage(
      String topic,
      String clusterId,
      int schemaId,
      boolean isSuccess,
      boolean isKey,
      String operation) {}

  @Override
  public void setCheckpoint(AgentSpan span, DataStreamsContext context) {}

  @Override
  public PathwayContext newPathwayContext() {
    return NoopPathwayContext.INSTANCE;
  }

  @Override
  public void add(StatsPoint statsPoint) {}

  @Override
  public int trySampleSchema(String topic) {
    return 0;
  }

  @Override
  public boolean canSampleSchema(String topic) {
    return false;
  }

  @Override
  public Schema getSchema(String schemaName, SchemaIterator iterator) {
    return null;
  }

  @Override
  public void setProduceCheckpoint(String type, String target) {}

  @Override
  public void setThreadServiceName(String serviceName) {}

  @Override
  public void clearThreadServiceName() {}

  @Override
  public void setConsumeCheckpoint(String type, String source, DataStreamsContextCarrier carrier) {}

  @Override
  public void setProduceCheckpoint(String type, String target, DataStreamsContextCarrier carrier) {}
}
