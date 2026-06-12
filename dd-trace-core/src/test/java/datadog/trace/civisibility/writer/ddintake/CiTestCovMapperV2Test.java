package datadog.trace.civisibility.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.GrowableBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.PropagationTags;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.msgpack.jackson.dataformat.MessagePackFactory;

@SuppressWarnings("unchecked")
public class CiTestCovMapperV2Test extends DDCoreJavaSpecification {

  private static final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

  @Test
  void testWritesMessage() throws Exception {
    List<DDSpan> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3L,
                Arrays.asList(
                    new TestReportFileEntry("source", BitSet.valueOf(new long[] {3, 5, 8})))));

    Map<String, Object> message = getMappedMessage(trace);

    List<Map<String, Object>> coverages = assertVersionAndGetCoverages(message, 2);
    assertEquals(1, coverages.size());

    Map<String, Object> coverage = coverages.get(0);
    assertCoverage(coverage, 1, 2, 3);

    List<Map<String, Object>> files = (List<Map<String, Object>>) coverage.get("files");
    assertEquals(1, files.size());
    assertFile(
        files.get(0), "source", new byte[] {3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8});
  }

  @Test
  void testWritesMessageWithMultipleFilesAndMultipleLines() throws Exception {
    List<DDSpan> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3L,
                Arrays.asList(
                    new TestReportFileEntry("sourceA", BitSet.valueOf(new long[] {3, 5, 8})),
                    new TestReportFileEntry("sourceB", BitSet.valueOf(new long[] {1, 255, 7})))));

    Map<String, Object> message = getMappedMessage(trace);

    List<Map<String, Object>> coverages = assertVersionAndGetCoverages(message, 2);
    assertEquals(1, coverages.size());

    Map<String, Object> coverage = coverages.get(0);
    assertCoverage(coverage, 1, 2, 3);

    List<Map<String, Object>> files = (List<Map<String, Object>>) coverage.get("files");
    assertEquals(2, files.size());
    assertFile(
        files.get(0), "sourceA", new byte[] {3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8});
    assertFile(
        files.get(1), "sourceB", new byte[] {1, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 7});
  }

  @Test
  void testWritesMessageWithMultipleReports() throws Exception {
    List<DDSpan> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3L,
                Arrays.asList(
                    new TestReportFileEntry("sourceA", BitSet.valueOf(new long[] {2, 17, 41})))),
            new TestReport(
                DDTraceId.from(1),
                2L,
                4L,
                Arrays.asList(
                    new TestReportFileEntry("sourceB", BitSet.valueOf(new long[] {11, 13, 55})))));

    Map<String, Object> message = getMappedMessage(trace);

    List<Map<String, Object>> coverages = assertVersionAndGetCoverages(message, 2);
    assertEquals(2, coverages.size());

    Map<String, Object> coverage0 = coverages.get(0);
    assertCoverage(coverage0, 1, 2, 3);
    List<Map<String, Object>> files0 = (List<Map<String, Object>>) coverage0.get("files");
    assertEquals(1, files0.size());
    assertFile(
        files0.get(0), "sourceA", new byte[] {2, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 0, 41});

    Map<String, Object> coverage1 = coverages.get(1);
    assertCoverage(coverage1, 1, 2, 4);
    List<Map<String, Object>> files1 = (List<Map<String, Object>>) coverage1.get("files");
    assertEquals(1, files1.size());
    assertFile(
        files1.get(0),
        "sourceB",
        new byte[] {11, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0, 55});
  }

  @Test
  void skipsSpansThatHaveNoReports() throws Exception {
    List<DDSpan> trace =
        givenTrace(
            null,
            new TestReport(
                DDTraceId.from(1),
                2L,
                3L,
                Arrays.asList(
                    new TestReportFileEntry("source", BitSet.valueOf(new long[] {83, 25, 48})))),
            null);

    Map<String, Object> message = getMappedMessage(trace);

    List<Map<String, Object>> coverages = assertVersionAndGetCoverages(message, 2);
    assertEquals(1, coverages.size());

    Map<String, Object> coverage = coverages.get(0);
    assertCoverage(coverage, 1, 2, 3);

    List<Map<String, Object>> files = (List<Map<String, Object>>) coverage.get("files");
    assertEquals(1, files.size());
    assertFile(
        files.get(0), "source", new byte[] {83, 0, 0, 0, 0, 0, 0, 0, 25, 0, 0, 0, 0, 0, 0, 0, 48});
  }

  @Test
  void skipsEmptyReports() throws Exception {
    List<DDSpan> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3L,
                Arrays.asList(
                    new TestReportFileEntry("source", BitSet.valueOf(new long[] {33, 53, 87})))),
            new TestReport(DDTraceId.from(1), 2L, 4L, Collections.emptyList()));

    Map<String, Object> message = getMappedMessage(trace);

    List<Map<String, Object>> coverages = assertVersionAndGetCoverages(message, 2);
    assertEquals(1, coverages.size());

    Map<String, Object> coverage = coverages.get(0);
    assertCoverage(coverage, 1, 2, 3);

    List<Map<String, Object>> files = (List<Map<String, Object>>) coverage.get("files");
    assertEquals(1, files.size());
    assertFile(
        files.get(0), "source", new byte[] {33, 0, 0, 0, 0, 0, 0, 0, 53, 0, 0, 0, 0, 0, 0, 0, 87});
  }

  @Test
  void skipsDuplicateReports() throws Exception {
    List<DDSpan> trace = new ArrayList<>();
    TestReport report =
        new TestReport(
            DDTraceId.from(1),
            2L,
            3L,
            Arrays.asList(new TestReportFileEntry("source", BitSet.valueOf(new long[] {3, 5, 8}))));

    trace.add(
        buildSpan(
            0,
            InternalSpanTypes.TEST,
            PropagationTags.factory().empty(),
            Collections.emptyMap(),
            PrioritySampling.SAMPLER_KEEP,
            new DummyTestContext(new DummyReportHolder(report))));
    trace.add(
        buildSpan(
            0,
            "testChild",
            PropagationTags.factory().empty(),
            Collections.emptyMap(),
            PrioritySampling.SAMPLER_KEEP,
            new DummyTestContext(new DummyReportHolder(report))));

    Map<String, Object> message = getMappedMessage(trace);

    List<Map<String, Object>> coverages = assertVersionAndGetCoverages(message, 2);
    assertEquals(1, coverages.size());

    Map<String, Object> coverage = coverages.get(0);
    assertCoverage(coverage, 1, 2, 3);

    List<Map<String, Object>> files = (List<Map<String, Object>>) coverage.get("files");
    assertEquals(1, files.size());
    assertFile(
        files.get(0), "source", new byte[] {3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8});
  }

  private List<DDSpan> givenTrace(TestReport... testReports) {
    List<DDSpan> trace = new ArrayList<>();
    for (TestReport testReport : testReports) {
      DummyReportHolder testReportHolder = new DummyReportHolder(testReport);
      trace.add(
          buildSpan(
              0,
              InternalSpanTypes.TEST,
              PropagationTags.factory().empty(),
              Collections.emptyMap(),
              PrioritySampling.SAMPLER_KEEP,
              new DummyTestContext(testReportHolder)));
    }
    return trace;
  }

  private Map<String, Object> getMappedMessage(List<DDSpan> trace) throws IOException {
    GrowableBuffer buffer = new GrowableBuffer(1024);
    CiTestCovMapperV2 mapper = new CiTestCovMapperV2(false);
    mapper.map(trace, new MsgPackWriter(buffer));

    ByteArrayWritableByteChannel channel = new ByteArrayWritableByteChannel();
    mapper.newPayload().withBody(1, buffer.slice()).writeTo(channel);

    return objectMapper.readValue(channel.toByteArray(), Map.class);
  }

  private List<Map<String, Object>> assertVersionAndGetCoverages(
      Map<String, Object> message, int version) {
    assertEquals(version, message.get("version"));
    return (List<Map<String, Object>>) message.get("coverages");
  }

  private void assertCoverage(
      Map<String, Object> coverage, int sessionId, int suiteId, int spanId) {
    assertEquals(sessionId, coverage.get("test_session_id"));
    assertEquals(suiteId, coverage.get("test_suite_id"));
    assertEquals(spanId, coverage.get("span_id"));
  }

  private void assertFile(Map<String, Object> file, String filename, byte[] bitmap) {
    assertEquals(filename, file.get("filename"));
    assertArrayEquals(bitmap, (byte[]) file.get("bitmap"));
  }

  private static final class DummyReportHolder implements CoverageStore {
    private final TestReport testReport;

    DummyReportHolder(TestReport testReport) {
      this.testReport = testReport;
    }

    @Override
    public TestReport getReport() {
      return testReport;
    }

    @Override
    public boolean report(DDTraceId testSessionId, Long testSuiteId, long spanId) {
      return false;
    }

    @Override
    public CoverageProbes getProbes() {
      return NoOpProbes.INSTANCE;
    }
  }

  private static final class DummyTestContext implements TestContext {
    private final CoverageStore coverageStore;

    DummyTestContext(CoverageStore coverageStore) {
      this.coverageStore = coverageStore;
    }

    @Override
    public CoverageStore getCoverageStore() {
      return coverageStore;
    }

    @Override
    public <T> void set(Class<T> key, T value) {}

    @Override
    public <T> T get(Class<T> key) {
      return null;
    }
  }

  private static final class ByteArrayWritableByteChannel implements WritableByteChannel {
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    @Override
    public int write(ByteBuffer src) throws IOException {
      int remaining = src.remaining();
      byte[] buffer = new byte[remaining];
      src.get(buffer);
      outputStream.write(buffer);
      return remaining;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() throws IOException {
      outputStream.close();
    }

    byte[] toByteArray() {
      return outputStream.toByteArray();
    }
  }
}
