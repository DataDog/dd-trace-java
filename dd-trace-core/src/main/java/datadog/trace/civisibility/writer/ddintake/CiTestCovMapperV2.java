package datadog.trace.civisibility.writer.ddintake;

import datadog.communication.http.HttpUtils;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.http.client.HttpRequestBody;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.coverage.TestReportHolder;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.Endpoint;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.intake.TrackType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.core.CoreSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CiTestCovMapperV2 implements RemoteMapper {

  private static final byte[] VERSION = "version".getBytes(StandardCharsets.UTF_8);
  private static final byte[] COVERAGES = "coverages".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TEST_SESSION_ID = "test_session_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TEST_SUITE_ID = "test_suite_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SPAN_ID = "span_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FILES = "files".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FILENAME = "filename".getBytes(StandardCharsets.UTF_8);
  private static final byte[] BITMAP = "bitmap".getBytes(StandardCharsets.UTF_8);

  private final int size;
  private final GrowableBuffer headerBuffer;
  private final MsgPackWriter headerWriter;
  private final boolean compressionEnabled;
  private int eventCount = 0;
  private int serializationTimeMillis = 0;

  public CiTestCovMapperV2(boolean compressionEnabled) {
    this(5 << 20, compressionEnabled);
  }

  private CiTestCovMapperV2(int size, boolean compressionEnabled) {
    this.size = size;
    this.compressionEnabled = compressionEnabled;
    headerBuffer = new GrowableBuffer(16);
    headerWriter = new MsgPackWriter(headerBuffer);
  }

  @Override
  public void map(List<? extends CoreSpan<?>> trace, Writable writable) {
    long serializationStartTimestamp = System.currentTimeMillis();

    for (CoreSpan<?> span : trace) {
      if (!isTestSpan(span)) {
        continue;
      }

      TestReport testReport = getTestReport(span);
      if (testReport == null || !testReport.isNotEmpty()) {
        continue;
      }

      DDTraceId testSessionId = testReport.getTestSessionId();
      Long testSuiteId = testReport.getTestSuiteId();

      int fieldCount = 2 + (testSessionId != null ? 1 : 0) + (testSuiteId != null ? 1 : 0);

      writable.startMap(fieldCount);

      if (testSessionId != null) {
        writable.writeUTF8(TEST_SESSION_ID);
        writable.writeLong(testSessionId.toLong());
      }

      if (testSuiteId != null) {
        writable.writeUTF8(TEST_SUITE_ID);
        writable.writeLong(testSuiteId);
      }

      writable.writeUTF8(SPAN_ID);
      writable.writeLong(testReport.getSpanId());

      Collection<TestReportFileEntry> fileEntries = testReport.getTestReportFileEntries();

      writable.writeUTF8(FILES);
      writable.startArray(fileEntries.size());

      for (TestReportFileEntry entry : fileEntries) {
        BitSet coveredLines = entry.getCoveredLines();
        boolean lineInfoPresent = coveredLines != null;

        writable.startMap(1 + (lineInfoPresent ? 1 : 0));

        writable.writeUTF8(FILENAME);
        writable.writeString(entry.getSourceFileName(), null);

        if (lineInfoPresent) {
          writable.writeUTF8(BITMAP);
          writable.writeBinary(coveredLines.toByteArray());
        }
      }

      eventCount++;
    }
    serializationTimeMillis += (int) (System.currentTimeMillis() - serializationStartTimestamp);
  }

  private static boolean isTestSpan(CoreSpan<?> span) {
    CharSequence type = span.getType();
    return type != null && type.toString().contentEquals(InternalSpanTypes.TEST);
  }

  private static TestReport getTestReport(CoreSpan<?> span) {
    if (span instanceof AgentSpan) {
      TestContext test =
          ((AgentSpan) span).getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
      if (test != null) {
        TestReportHolder probes = test.getCoverageStore();
        if (probes != null) {
          return probes.getReport();
        }
      }
    }
    return null;
  }

  private void writeHeader() {
    headerWriter.startMap(2);

    headerWriter.writeUTF8(VERSION);
    headerWriter.writeInt(2);

    headerWriter.writeUTF8(COVERAGES);
    headerWriter.startArray(eventCount);
  }

  @Override
  public Payload newPayload() {
    writeHeader();

    CiVisibilityMetricCollector metricCollector = InstrumentationBridge.getMetricCollector();
    metricCollector.add(
        CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_EVENTS_COUNT,
        eventCount,
        Endpoint.CODE_COVERAGE);
    metricCollector.add(
        CiVisibilityDistributionMetric.ENDPOINT_PAYLOAD_EVENTS_SERIALIZATION_MS,
        serializationTimeMillis,
        Endpoint.CODE_COVERAGE);

    return new PayloadV2(compressionEnabled).withHeader(headerBuffer.slice());
  }

  @Override
  public int messageBufferSize() {
    return size;
  }

  @Override
  public void reset() {
    eventCount = 0;
    serializationTimeMillis = 0;
  }

  @Override
  public String endpoint() {
    return TrackType.CITESTCOV + "/v2";
  }

  private static class PayloadV2 extends Payload {

    // backend requires _some_ JSON to be present
    private static final HttpRequestBody DUMMY_JSON_BODY = HttpRequestBody.of("{\"dummy\":true}");

    private final boolean compressionEnabled;

    ByteBuffer header = null;

    private PayloadV2(boolean compressionEnabled) {
      this.compressionEnabled = compressionEnabled;
    }

    PayloadV2 withHeader(ByteBuffer header) {
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
    public HttpRequestBody toRequest() {
      List<ByteBuffer> buffers;
      if (traceCount() == 0) {
        // If traceCount is 0, we write a map with 0 elements in MsgPack format.
        buffers = Collections.singletonList(msgpackMapHeader(0));
      } else if (header != null) {
        buffers = Arrays.asList(header, body);
      } else {
        buffers = Collections.singletonList(body);
      }
      HttpRequestBody coverageBody = HttpUtils.msgpackRequestBodyOf(buffers);

      HttpRequestBody.MultipartBuilder multipartBuilder = HttpRequestBody.multipart();
      multipartBuilder.addFormDataPart("coverage1", "coverage1.msgpack", coverageBody);
      multipartBuilder.addFormDataPart("event", "event.json", DUMMY_JSON_BODY);
      HttpRequestBody multipartBody = multipartBuilder.build();

      return compressionEnabled ? HttpUtils.gzippedRequestBodyOf(multipartBody) : multipartBody;
    }
  }
}
