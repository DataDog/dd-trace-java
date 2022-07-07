package datadog.trace.civisibility.writer.ddintake;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.Writable;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

public class CiTestCycleMapperV1 implements RemoteMapper {

  public static final byte[] VERSION = "version".getBytes(StandardCharsets.UTF_8);
  public static final byte[] METADATA = "metadata".getBytes(StandardCharsets.UTF_8);
  public static final byte[] METADATA_ASTERISK = "*".getBytes(StandardCharsets.UTF_8);
  public static final byte[] EVENTS = "events".getBytes(StandardCharsets.UTF_8);
  public static final byte[] ENV = "env".getBytes(StandardCharsets.UTF_8);
  public static final byte[] TYPE = "type".getBytes(StandardCharsets.UTF_8);
  public static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  public static final UTF8BytesString TEST_TYPE = UTF8BytesString.create("test");
  public static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create("span");

  private final WellKnownTags wellKnownTags;
  private final int size;

  public CiTestCycleMapperV1(WellKnownTags wellKnownTags, int size) {
    this.wellKnownTags = wellKnownTags;
    this.size = size;
  }

  public CiTestCycleMapperV1(WellKnownTags wellKnownTags) {
    this(wellKnownTags, 5 << 20);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    writable.startMap(3);
    /* 1  */
    writable.writeUTF8(VERSION);
    writable.writeInt(1);
    /* 2  */
    writable.writeUTF8(METADATA);
    writable.startMap(1);
    /* 2,1 */
    writable.writeUTF8(METADATA_ASTERISK);
    writable.startMap(3);
    /* 2,1,1 */
    writable.writeUTF8(ENV);
    writable.writeUTF8(wellKnownTags.getEnv());
    /* 2,1,2 */
    writable.writeUTF8(RUNTIME_ID);
    writable.writeUTF8(wellKnownTags.getRuntimeId());
    /* 2,1,3 */
    writable.writeUTF8(LANGUAGE);
    writable.writeUTF8(wellKnownTags.getLanguage());
    /* 3  */
    writable.writeUTF8(EVENTS);
    writable.startArray(trace.size());
    for (int i = 0; i < trace.size(); i++) {
      final CoreSpan<?> span = trace.get(i);

      writable.startMap(3);
      /* 1 */
      writable.writeUTF8(TYPE);
      if (TEST_TYPE.equals(span.getType())) {
        writable.writeUTF8(TEST_TYPE);
      } else {
        writable.writeUTF8(SPAN_TYPE);
      }
      /* 2 */
      writable.writeUTF8(VERSION);
      writable.writeInt(1);
      /* 3 */
      writable.writeUTF8(CONTENT);
      writable.startMap(11);
      /* 1  */
      writable.writeUTF8(SERVICE);
      writable.writeString(span.getServiceName(), null);
      /* 2  */
      writable.writeUTF8(NAME);
      writable.writeObject(span.getOperationName(), null);
      /* 3  */
      writable.writeUTF8(RESOURCE);
      writable.writeObject(span.getResourceName(), null);
      /* 4  */
      writable.writeUTF8(TRACE_ID);
      writable.writeLong(span.getTraceId().toLong());
      /* 5  */
      writable.writeUTF8(SPAN_ID);
      writable.writeLong(span.getSpanId().toLong());
      /* 6  */
      writable.writeUTF8(PARENT_ID);
      writable.writeLong(span.getParentId().toLong());
      /* 7  */
      writable.writeUTF8(START);
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeUTF8(DURATION);
      writable.writeLong(span.getDurationNano());
      /* 9 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 10 (meta), 11 (metrics) */
      span.processTagsAndBaggage(metaWriter.withWritable(writable));
    }
  }

  private final MetaWriter metaWriter = new MetaWriter();

  @Override
  public Payload newPayload() {
    return new PayloadV1();
  }

  @Override
  public int messageBufferSize() {
    return size;
  }

  @Override
  public void reset() {}

  @Override
  public String endpoint() {
    return "citestcycle/v1";
  }

  private static final class MetaWriter extends MetadataConsumer {

    private Writable writable;

    MetaWriter withWritable(Writable writable) {
      this.writable = writable;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      int metaSize =
          metadata.getBaggage().size()
              + metadata.getTags().size()
              + (null == metadata.getHttpStatusCode() ? 0 : 1);
      int metricsSize = 0;
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        if (tag.getValue() instanceof Number) {
          ++metricsSize;
          --metaSize;
        }
      }
      writable.writeUTF8(METRICS);
      writable.startMap(metricsSize);
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        if (entry.getValue() instanceof Number) {
          writable.writeString(entry.getKey(), null);
          writable.writeObject(entry.getValue(), null);
        }
      }

      writable.writeUTF8(META);
      writable.startMap(metaSize);
      // we don't need to deduplicate any overlap between tags and baggage here
      // since they will be accumulated into maps in the same order downstream,
      // we just need to be sure that the size is the same as the number of elements
      for (Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        writable.writeString(entry.getKey(), null);
        writable.writeString(entry.getValue(), null);
      }
      if (null != metadata.getHttpStatusCode()) {
        writable.writeUTF8(HTTP_STATUS);
        writable.writeUTF8(metadata.getHttpStatusCode());
      }
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        if (!(entry.getValue() instanceof Number)) {
          writable.writeString(entry.getKey(), null);
          writable.writeObjectString(entry.getValue(), null);
        }
      }
    }
  }

  private static class PayloadV1 extends Payload {

    @Override
    public int sizeInBytes() {
      return body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      // If traceCount is 0, we write a map with 0 elements in MsgPack format.
      if (traceCount() == 0) {
        ByteBuffer header = msgpackMapHeader(0);
        while (header.hasRemaining()) {
          channel.write(header);
        }
      } else {
        while (body.hasRemaining()) {
          channel.write(body);
        }
      }
    }

    @Override
    public RequestBody toRequest() {
      // If traceCount is 0, we write a map with 0 elements in MsgPack format.
      if (traceCount() == 0) {
        return msgpackRequestBodyOf(Collections.singletonList(msgpackMapHeader(0)));
      } else {
        return msgpackRequestBodyOf(Collections.singletonList(body));
      }
    }
  }
}
