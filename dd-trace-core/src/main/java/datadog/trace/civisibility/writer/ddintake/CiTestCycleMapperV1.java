package datadog.trace.civisibility.writer.ddintake;

import static datadog.communication.http.OkHttpUtils.gzippedMsgpackRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.WellKnownTags;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.util.Strings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import okhttp3.RequestBody;

public class CiTestCycleMapperV1 implements RemoteMapper {

  private static final byte[] VERSION = "version".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METADATA = "metadata".getBytes(StandardCharsets.UTF_8);
  private static final byte[] METADATA_ASTERISK = "*".getBytes(StandardCharsets.UTF_8);
  private static final byte[] EVENTS = "events".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ENV = "env".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TYPE = "type".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  private static final UTF8BytesString SPAN_TYPE = UTF8BytesString.create("span");

  private static final Collection<String> DEFAULT_TOP_LEVEL_TAGS =
      Arrays.asList(Tags.TEST_SESSION_ID, Tags.TEST_MODULE_ID, Tags.TEST_SUITE_ID);

  private final WellKnownTags wellKnownTags;
  private final Collection<String> topLevelTags;
  private final int size;
  private final GrowableBuffer headerBuffer;
  private final MsgPackWriter headerWriter;
  private final boolean compressionEnabled;
  private int eventCount = 0;

  public CiTestCycleMapperV1(WellKnownTags wellKnownTags, boolean compressionEnabled) {
    this(wellKnownTags, DEFAULT_TOP_LEVEL_TAGS, 5 << 20, compressionEnabled);
  }

  private CiTestCycleMapperV1(
      WellKnownTags wellKnownTags,
      Collection<String> topLevelTags,
      int size,
      boolean compressionEnabled) {
    this.wellKnownTags = wellKnownTags;
    this.topLevelTags = topLevelTags;
    this.size = size;
    this.compressionEnabled = compressionEnabled;
    headerBuffer = new GrowableBuffer(16);
    headerWriter = new MsgPackWriter(headerBuffer);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    for (final CoreSpan<?> span : trace) {
      int topLevelTagsCount = 0;
      for (String topLevelTag : topLevelTags) {
        if (span.getTag(topLevelTag) != null) {
          topLevelTagsCount++;
        }
      }

      UTF8BytesString type;
      Long traceId;
      Long spanId;
      Long parentId;
      int version;
      CharSequence spanType = span.getType();
      if (equals(InternalSpanTypes.TEST, spanType)) {
        type = InternalSpanTypes.TEST;
        traceId = span.getTraceId().toLong();
        spanId = span.getSpanId();
        parentId = span.getParentId();
        // If there are no top-level tags,
        // this is a test span that is not a part of a test suite,
        // i.e. emitted by framework that does not support testing suites yet
        version = topLevelTagsCount > 0 ? 2 : 1;

      } else if (equals(InternalSpanTypes.TEST_SUITE_END, spanType)) {
        type = InternalSpanTypes.TEST_SUITE_END;
        traceId = null;
        spanId = null;
        parentId = null;
        version = 1;

      } else if (equals(InternalSpanTypes.TEST_MODULE_END, spanType)) {
        type = InternalSpanTypes.TEST_MODULE_END;
        traceId = null;
        spanId = null;
        parentId = null;
        version = 1;

      } else if (equals(InternalSpanTypes.TEST_SESSION_END, spanType)) {
        type = InternalSpanTypes.TEST_SESSION_END;
        traceId = null;
        spanId = null;
        parentId = null;
        version = 1;

      } else {
        type = SPAN_TYPE;
        traceId = span.getTraceId().toLong();
        spanId = span.getSpanId();
        parentId = span.getParentId();
        version = 1;
      }

      int contentChildrenCount =
          8
              + (traceId != null ? 1 : 0)
              + (spanId != null ? 1 : 0)
              + (parentId != null ? 1 : 0)
              + topLevelTagsCount;

      writable.startMap(3);
      /* 1 */
      writable.writeUTF8(TYPE);
      writable.writeUTF8(type);
      /* 2 */
      writable.writeUTF8(VERSION);
      writable.writeInt(version);
      /* 3 */
      writable.writeUTF8(CONTENT);
      writable.startMap(contentChildrenCount);

      if (traceId != null) {
        writable.writeUTF8(TRACE_ID);
        writable.writeUnsignedLong(traceId);
      }
      if (spanId != null) {
        writable.writeUTF8(SPAN_ID);
        writable.writeUnsignedLong(spanId);
      }
      if (parentId != null) {
        writable.writeUTF8(PARENT_ID);
        writable.writeUnsignedLong(parentId);
      }

      for (String topLevelTag : topLevelTags) {
        Object tagValue = span.getTag(topLevelTag);
        if (tagValue != null) {
          writable.writeString(topLevelTag, null);

          if (tagValue instanceof Number) {
            writable.writeObject(tagValue, null);
          } else {
            writable.writeObjectString(tagValue, null);
          }

          span.removeTag(topLevelTag);
        }
      }

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
      writable.writeUTF8(START);
      writable.writeLong(span.getStartTime());
      /* 5  */
      writable.writeUTF8(DURATION);
      writable.writeLong(span.getDurationNano());
      /* 6 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 7 (meta), 8 (metrics) */
      span.processTagsAndBaggage(metaWriter.withWritable(writable));
    }
    eventCount += trace.size();
  }

  private static boolean equals(CharSequence a, CharSequence b) {
    return a == null && b == null
        || a != null && b != null && Objects.equals(a.toString(), b.toString());
  }

  private final MetaWriter metaWriter = new MetaWriter();

  private void writeHeader() {
    headerWriter.startMap(3);
    /* 1  */
    headerWriter.writeUTF8(VERSION);
    headerWriter.writeInt(1);
    /* 2  */
    headerWriter.writeUTF8(METADATA);
    headerWriter.startMap(1);
    /* 2,1 */
    headerWriter.writeUTF8(METADATA_ASTERISK);
    headerWriter.startMap(3);
    /* 2,1,1 */
    headerWriter.writeUTF8(ENV);
    headerWriter.writeUTF8(wellKnownTags.getEnv());
    /* 2,1,2 */
    headerWriter.writeUTF8(RUNTIME_ID);
    headerWriter.writeUTF8(wellKnownTags.getRuntimeId());
    /* 2,1,3 */
    headerWriter.writeUTF8(LANGUAGE);
    headerWriter.writeUTF8(wellKnownTags.getLanguage());
    /* 3  */
    headerWriter.writeUTF8(EVENTS);
    headerWriter.startArray(eventCount);
  }

  @Override
  public Payload newPayload() {
    writeHeader();
    return new PayloadV1(compressionEnabled).withHeader(headerBuffer.slice());
  }

  @Override
  public int messageBufferSize() {
    return size;
  }

  @Override
  public void reset() {
    eventCount = 0;
  }

  @Override
  public String endpoint() {
    return "citestcycle/v1";
  }

  private static final class MetaWriter implements MetadataConsumer {

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
        Object value = entry.getValue();
        if (!(value instanceof Number)) {
          writable.writeString(entry.getKey(), null);
          if (!(value instanceof Iterable)) {
            writable.writeObjectString(value, null);
          } else {
            String serializedValue = Strings.toJson((Iterable<String>) value);
            writable.writeString(serializedValue, null);
          }
        }
      }
    }
  }

  private static class PayloadV1 extends Payload {

    private final boolean compressionEnabled;

    ByteBuffer header = null;

    private PayloadV1(boolean compressionEnabled) {
      this.compressionEnabled = compressionEnabled;
    }

    PayloadV1 withHeader(ByteBuffer header) {
      this.header = header;
      return this;
    }

    @Override
    public int sizeInBytes() {
      if (traceCount() == 0) {
        return msgpackMapHeaderSize(0);
      }
      int size = body.remaining();
      if (header != null) {
        size += header.remaining();
      }
      return size;
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      // If traceCount is 0, we write a map with 0 elements in MsgPack format.
      if (traceCount() == 0) {
        ByteBuffer emptyDict = msgpackMapHeader(0);
        while (emptyDict.hasRemaining()) {
          channel.write(emptyDict);
        }
      } else {
        if (header != null) {
          while (header.hasRemaining()) {
            channel.write(header);
          }
        }
        while (body.hasRemaining()) {
          channel.write(body);
        }
      }
    }

    @Override
    public RequestBody toRequest() {
      // If traceCount is 0, we write a map with 0 elements in MsgPack format.
      List<ByteBuffer> buffers;
      if (traceCount() == 0) {
        buffers = Collections.singletonList(msgpackMapHeader(0));
      } else if (header != null) {
        buffers = Arrays.asList(header, body);
      } else {
        buffers = Collections.singletonList(body);
      }
      return compressionEnabled
          ? gzippedMsgpackRequestBodyOf(buffers)
          : msgpackRequestBodyOf(buffers);
    }
  }
}
