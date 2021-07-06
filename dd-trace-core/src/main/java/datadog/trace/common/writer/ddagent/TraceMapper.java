package datadog.trace.common.writer.ddagent;

import datadog.communication.serialization.Mapper;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import java.util.List;

public interface TraceMapper extends Mapper<List<? extends CoreSpan<?>>> {

  UTF8BytesString HTTP_STATUS = UTF8BytesString.create(Tags.HTTP_STATUS);
  UTF8BytesString THREAD_NAME = UTF8BytesString.create(DDTags.THREAD_NAME);
  UTF8BytesString THREAD_ID = UTF8BytesString.create(DDTags.THREAD_ID);
  UTF8BytesString SAMPLING_PRIORITY_KEY = UTF8BytesString.create("_sampling_priority_v1");
  UTF8BytesString ORIGIN_KEY = UTF8BytesString.create(DDTags.ORIGIN_KEY);

  Payload newPayload();

  int messageBufferSize();

  void reset();

  String endpoint();
}
