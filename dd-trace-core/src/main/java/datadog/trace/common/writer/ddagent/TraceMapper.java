package datadog.trace.common.writer.ddagent;

import datadog.trace.core.DDSpan;
import datadog.trace.core.serialization.msgpack.Mapper;
import java.util.List;

public interface TraceMapper extends Mapper<List<DDSpan>> {

  Payload newPayload();

  void reset();
}
