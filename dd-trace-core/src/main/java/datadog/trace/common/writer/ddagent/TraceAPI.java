package datadog.trace.common.writer.ddagent;

public interface TraceAPI {

  TraceMapper selectTraceMapper();

  Response sendSerializedTraces(final Payload payload);

  void addResponseListener(final DDAgentResponseListener listener);
}
