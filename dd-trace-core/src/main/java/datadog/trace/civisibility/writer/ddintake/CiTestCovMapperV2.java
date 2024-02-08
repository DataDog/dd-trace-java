package datadog.trace.civisibility.writer.ddintake;

import static datadog.communication.http.OkHttpUtils.gzippedRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.jsonRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;

import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.Writable;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.coverage.TestReportHolder;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

public class CiTestCovMapperV2 implements RemoteMapper {

  private static final byte[] VERSION = "version".getBytes(StandardCharsets.UTF_8);
  private static final byte[] COVERAGES = "coverages".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TEST_SESSION_ID = "test_session_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TEST_SUITE_ID = "test_suite_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SPAN_ID = "span_id".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FILES = "files".getBytes(StandardCharsets.UTF_8);
  private static final byte[] FILENAME = "filename".getBytes(StandardCharsets.UTF_8);
  private static final byte[] SEGMENTS = "segments".getBytes(StandardCharsets.UTF_8);

  private final int size;
  private final GrowableBuffer headerBuffer;
  private final MsgPackWriter headerWriter;
  private final boolean compressionEnabled;
  private int eventCount = 0;

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
    List<TestReport> testReports =
        trace.stream()
            // only consider test spans, since children spans
            // share test reports with their parents
            .filter(CiTestCovMapperV2::isTestSpan)
            .map(CiTestCovMapperV2::getTestReport)
            .filter(Objects::nonNull)
            .filter(TestReport::isNotEmpty)
            .collect(Collectors.toList());

    for (TestReport testReport : testReports) {
      Long testSessionId = testReport.getTestSessionId();
      Long testSuiteId = testReport.getTestSuiteId();

      int fieldCount = 2 + (testSessionId != null ? 1 : 0) + (testSuiteId != null ? 1 : 0);

      writable.startMap(fieldCount);

      if (testSessionId != null) {
        writable.writeUTF8(TEST_SESSION_ID);
        writable.writeLong(testSessionId);
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
        writable.startMap(2);

        writable.writeUTF8(FILENAME);
        writable.writeString(entry.getSourceFileName(), null);

        Collection<TestReportFileEntry.Segment> segments = entry.getSegments();

        writable.writeUTF8(SEGMENTS);
        writable.startArray(segments.size());

        for (TestReportFileEntry.Segment segment : segments) {
          writable.startArray(5);
          writable.writeInt(segment.getStartLine());
          writable.writeInt(segment.getStartColumn());
          writable.writeInt(segment.getEndLine());
          writable.writeInt(segment.getEndColumn());
          writable.writeInt(segment.getNumberOfExecutions());
        }
      }
    }

    eventCount += testReports.size();
  }

  private static boolean isTestSpan(CoreSpan<?> span) {
    CharSequence type = span.getType();
    return type != null && type.toString().contentEquals(InternalSpanTypes.TEST);
  }

  private static TestReport getTestReport(CoreSpan<?> span) {
    if (span instanceof AgentSpan) {
      TestReportHolder probes =
          ((AgentSpan) span).getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
      if (probes != null) {
        return probes.getReport();
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
    return new PayloadV2(compressionEnabled).withHeader(headerBuffer.slice());
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
    return TrackType.CITESTCOV + "/v2";
  }

  private static class PayloadV2 extends Payload {

    // backend requires _some_ JSON to be present
    private static final RequestBody DUMMY_JSON_BODY =
        jsonRequestBodyOf("{\"dummy\":true}".getBytes());

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
    public RequestBody toRequest() {
      List<ByteBuffer> buffers;
      if (traceCount() == 0) {
        // If traceCount is 0, we write a map with 0 elements in MsgPack format.
        buffers = Collections.singletonList(msgpackMapHeader(0));
      } else if (header != null) {
        buffers = Arrays.asList(header, body);
      } else {
        buffers = Collections.singletonList(body);
      }
      RequestBody coverageBody = msgpackRequestBodyOf(buffers);

      MultipartBody multipartBody =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart("coverage1", "coverage1.msgpack", coverageBody)
              .addFormDataPart("event", "event.json", DUMMY_JSON_BODY)
              .build();

      return compressionEnabled ? gzippedRequestBodyOf(multipartBody) : multipartBody;
    }
  }
}
