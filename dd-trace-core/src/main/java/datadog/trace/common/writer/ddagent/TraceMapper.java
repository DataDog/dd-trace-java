package datadog.trace.common.writer.ddagent;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.serialization.Mapper;
import java.util.List;

public interface TraceMapper extends Mapper<List<? extends CoreSpan<?>>> {

  Payload newPayload();

  int messageBufferSize();

  void reset();

  String endpoint();
}
