package datadog.trace.common.writer.common;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.communication.serialization.Mapper;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.ddagent.Payload;
import datadog.trace.core.CoreSpan;
import java.nio.charset.StandardCharsets;
import java.util.List;

public interface RemoteMapper extends Mapper<List<? extends CoreSpan<?>>> {

  byte[] RUNTIME_ID = DDTags.RUNTIME_ID_TAG.getBytes(StandardCharsets.ISO_8859_1);
  byte[] LANGUAGE = DDTags.LANGUAGE_TAG_KEY.getBytes(StandardCharsets.ISO_8859_1);

  byte[] SERVICE = "service".getBytes(ISO_8859_1);
  byte[] NAME = "name".getBytes(ISO_8859_1);
  byte[] RESOURCE = "resource".getBytes(ISO_8859_1);
  byte[] TRACE_ID = "trace_id".getBytes(ISO_8859_1);
  byte[] SPAN_ID = "span_id".getBytes(ISO_8859_1);
  byte[] PARENT_ID = "parent_id".getBytes(ISO_8859_1);
  byte[] START = "start".getBytes(ISO_8859_1);
  byte[] DURATION = "duration".getBytes(ISO_8859_1);
  byte[] TYPE = "type".getBytes(ISO_8859_1);
  byte[] ERROR = "error".getBytes(ISO_8859_1);
  byte[] METRICS = "metrics".getBytes(ISO_8859_1);
  byte[] META = "meta".getBytes(ISO_8859_1);

  UTF8BytesString HTTP_STATUS = UTF8BytesString.create(Tags.HTTP_STATUS);

  Payload newPayload();

  int messageBufferSize();

  void reset();

  String endpoint();
}
