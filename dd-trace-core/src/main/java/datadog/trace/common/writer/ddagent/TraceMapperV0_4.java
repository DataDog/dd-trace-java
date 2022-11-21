package datadog.trace.common.writer.ddagent;

import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.Writable;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.common.writer.Payload;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import okhttp3.RequestBody;

public final class TraceMapperV0_4 implements TraceMapper {

  private final int size;

  public TraceMapperV0_4(int size) {
    this.size = size;
  }

  public TraceMapperV0_4() {
    this(5 << 20);
  }

  private static final class MetaWriter extends MetadataConsumer {

    private Writable writable;
    private boolean writeSamplingPriority;

    MetaWriter withWritable(Writable writable) {
      this.writable = writable;
      return this;
    }

    MetaWriter withWriteSamplingPriority(final boolean writeSamplingPriority) {
      this.writeSamplingPriority = writeSamplingPriority;
      return this;
    }

    @Override
    public void accept(Metadata metadata) {
      int metaSize =
          metadata.getBaggage().size()
              + metadata.getTags().size()
              + (null == metadata.getHttpStatusCode() ? 0 : 1)
              + (null == metadata.getOrigin() ? 0 : 1)
              + 1;
      int metricsSize =
          (writeSamplingPriority && metadata.hasSamplingPriority() ? 1 : 0)
              + (metadata.measured() ? 1 : 0)
              + (metadata.topLevel() ? 1 : 0)
              + 1;
      for (Map.Entry<String, Object> tag : metadata.getTags().entrySet()) {
        if (tag.getValue() instanceof Number) {
          ++metricsSize;
          --metaSize;
        }
      }
      writable.writeUTF8(METRICS);
      writable.startMap(metricsSize);
      if (writeSamplingPriority && metadata.hasSamplingPriority()) {
        writable.writeUTF8(SAMPLING_PRIORITY_KEY);
        writable.writeInt(metadata.samplingPriority());
      }
      if (metadata.measured()) {
        writable.writeUTF8(InstrumentationTags.DD_MEASURED);
        writable.writeInt(1);
      }
      if (metadata.topLevel()) {
        writable.writeUTF8(InstrumentationTags.DD_TOP_LEVEL);
        writable.writeInt(1);
      }
      writable.writeUTF8(THREAD_ID);
      writable.writeLong(metadata.getThreadId());
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
      writable.writeUTF8(THREAD_NAME);
      writable.writeUTF8(metadata.getThreadName());
      if (null != metadata.getHttpStatusCode()) {
        writable.writeUTF8(HTTP_STATUS);
        writable.writeUTF8(metadata.getHttpStatusCode());
      }
      if (null != metadata.getOrigin()) {
        writable.writeUTF8(ORIGIN_KEY);
        writable.writeString(metadata.getOrigin(), null);
      }
      for (Map.Entry<String, Object> entry : metadata.getTags().entrySet()) {
        if (!(entry.getValue() instanceof Number)) {
          writable.writeString(entry.getKey(), null);
          writable.writeObjectString(entry.getValue(), null);
        }
      }
    }
  }

  private final MetaWriter metaWriter = new MetaWriter();

  @Override
  public void map(List<? extends CoreSpan<?>> trace, final Writable writable) {
    writable.startArray(trace.size());
    for (int i = 0; i < trace.size(); i++) {
      final CoreSpan<?> span = trace.get(i);
      writable.startMap(12);
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
      writable.writeLong(span.getSpanId());
      /* 6  */
      writable.writeUTF8(PARENT_ID);
      writable.writeLong(span.getParentId());
      /* 7  */
      writable.writeUTF8(START);
      writable.writeLong(span.getStartTime());
      /* 8  */
      writable.writeUTF8(DURATION);
      writable.writeLong(span.getDurationNano());
      /* 9  */
      writable.writeUTF8(TYPE);
      writable.writeString(span.getType(), null);
      /* 10 */
      writable.writeUTF8(ERROR);
      writable.writeInt(span.getError());
      /* 11, 12 */
      span.processTagsAndBaggage(
          metaWriter
              .withWritable(writable)
              .withWriteSamplingPriority(i == 0 || i == trace.size() - 1));
    }
  }

  @Override
  public Payload newPayload() {
    return new PayloadV0_4();
  }

  @Override
  public int messageBufferSize() {
    return size; // 5MB
  }

  @Override
  public void reset() {}

  @Override
  public String endpoint() {
    return "v0.4";
  }

  private static class PayloadV0_4 extends Payload {

    @Override
    public int sizeInBytes() {
      return msgpackArrayHeaderSize(traceCount()) + body.remaining();
    }

    @Override
    public void writeTo(WritableByteChannel channel) throws IOException {
      ByteBuffer header = msgpackArrayHeader(traceCount());
      while (header.hasRemaining()) {
        channel.write(header);
      }
      while (body.hasRemaining()) {
        channel.write(body);
      }
    }

    @Override
    public RequestBody toRequest() {
      return msgpackRequestBodyOf(Arrays.asList(msgpackArrayHeader(traceCount()), body));
    }
  }
}
