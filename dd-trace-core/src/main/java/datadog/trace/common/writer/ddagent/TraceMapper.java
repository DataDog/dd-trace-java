package datadog.trace.common.writer.ddagent;

import datadog.trace.core.DDSpanData;
import datadog.trace.core.serialization.msgpack.Mapper;
import java.util.List;

public interface TraceMapper extends Mapper<List<? extends DDSpanData>> {

  Payload newPayload();

  void reset();
}
