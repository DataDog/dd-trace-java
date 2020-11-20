package datadog.trace.common.writer.ddagent;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanData;
import datadog.trace.core.serialization.Mapper;
import java.util.List;

public interface TraceMapper extends Mapper<List<? extends AgentSpanData>> {

  Payload newPayload();

  int messageBufferSize();

  void reset();

  String endpoint();
}
