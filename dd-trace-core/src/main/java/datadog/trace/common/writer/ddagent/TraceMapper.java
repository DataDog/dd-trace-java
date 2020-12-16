package datadog.trace.common.writer.ddagent;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.serialization.Mapper;
import java.util.List;

public interface TraceMapper extends Mapper<List<? extends CoreSpan<?>>> {

  UTF8BytesString THREAD_NAME = UTF8BytesString.create(DDTags.THREAD_NAME);
  UTF8BytesString THREAD_ID = UTF8BytesString.create(DDTags.THREAD_ID);
  UTF8BytesString SAMPLING_PRIORITY_KEY = UTF8BytesString.create("_sampling_priority_v1");

  Payload newPayload();

  int messageBufferSize();

  void reset();

  String endpoint();
}
