package datadog.trace.common.writer;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.Writable;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreSpan;
import java.util.List;

public interface RemoteMapper extends Mapper<List<? extends CoreSpan<?>>> {

  RemoteMapper NO_OP = new NoopRemoteMapper();

  static final byte[] RUNTIME_ID = DDTags.RUNTIME_ID_TAG.getBytes(UTF_8);
  static final byte[] LANGUAGE = DDTags.LANGUAGE_TAG_KEY.getBytes(UTF_8);

  static final byte[] SERVICE = "service".getBytes(UTF_8);
  static final byte[] NAME = "name".getBytes(UTF_8);
  static final byte[] RESOURCE = "resource".getBytes(UTF_8);
  static final byte[] TRACE_ID = "trace_id".getBytes(UTF_8);
  static final byte[] SPAN_ID = "span_id".getBytes(UTF_8);
  static final byte[] PARENT_ID = "parent_id".getBytes(UTF_8);
  static final byte[] START = "start".getBytes(UTF_8);
  static final byte[] DURATION = "duration".getBytes(UTF_8);
  static final byte[] TYPE = "type".getBytes(UTF_8);
  static final byte[] ERROR = "error".getBytes(UTF_8);
  static final byte[] METRICS = "metrics".getBytes(UTF_8);
  static final byte[] META = "meta".getBytes(UTF_8);

  UTF8BytesString HTTP_STATUS = UTF8BytesString.create(Tags.HTTP_STATUS);

  Payload newPayload();

  int messageBufferSize();

  String endpoint();

  class NoopRemoteMapper implements RemoteMapper {

    @Override
    public void map(List<? extends CoreSpan<?>> data, Writable packer) {}

    @Override
    public Payload newPayload() {
      return null;
    }

    @Override
    public int messageBufferSize() {
      return 0;
    }

    @Override
    public String endpoint() {
      return null;
    }
  }
}
