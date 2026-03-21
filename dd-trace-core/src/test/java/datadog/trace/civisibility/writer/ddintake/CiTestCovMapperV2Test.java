package datadog.trace.civisibility.writer.ddintake;

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
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.test.DDCoreSpecification;
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

class CiTestCovMapperV2Test extends DDCoreSpecification {

  private ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

  @Test
  void testWritesMessage() throws Exception {
    List<? extends CoreSpan<?>> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3,
                Collections.singletonList(
                    new TestReportFileEntry("source", BitSet.valueOf(new long[] {3, 5, 8})))));

    Map message = getMappedMessage(trace);

    List coverages = (List) message.get("coverages");
    assertEquals(2, message.get("version"));
    assertEquals(1, coverages.size());
    Map coverage = (Map) coverages.get(0);
    assertEquals(1L, ((Number) coverage.get("test_session_id")).longValue());
    assertEquals(2L, ((Number) coverage.get("test_suite_id")).longValue());
    assertEquals(3L, ((Number) coverage.get("span_id")).longValue());
    List files = (List) coverage.get("files");
    assertEquals(1, files.size());
    Map file = (Map) files.get(0);
    assertEquals("source", file.get("filename"));
    assertByteArray(
        new int[] {3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8}, file.get("bitmap"));
  }

  @Test
  void testWritesMessageWithMultipleFilesAndMultipleLines() throws Exception {
    List<? extends CoreSpan<?>> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3,
                Arrays.asList(
                    new TestReportFileEntry("sourceA", BitSet.valueOf(new long[] {3, 5, 8})),
                    new TestReportFileEntry("sourceB", BitSet.valueOf(new long[] {1, 255, 7})))));

    Map message = getMappedMessage(trace);

    List coverages = (List) message.get("coverages");
    assertEquals(2, message.get("version"));
    assertEquals(1, coverages.size());
    Map coverage = (Map) coverages.get(0);
    List files = (List) coverage.get("files");
    assertEquals(2, files.size());
    Map fileA = (Map) files.get(0);
    assertEquals("sourceA", fileA.get("filename"));
    assertByteArray(
        new int[] {3, 0, 0, 0, 0, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0, 8}, fileA.get("bitmap"));
    Map fileB = (Map) files.get(1);
    assertEquals("sourceB", fileB.get("filename"));
    assertByteArray(
        new int[] {1, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0, 0, 0, 7}, fileB.get("bitmap"));
  }

  @Test
  void testWritesMessageWithMultipleReports() throws Exception {
    List<? extends CoreSpan<?>> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3,
                Collections.singletonList(
                    new TestReportFileEntry("sourceA", BitSet.valueOf(new long[] {2, 17, 41})))),
            new TestReport(
                DDTraceId.from(1),
                2L,
                4,
                Collections.singletonList(
                    new TestReportFileEntry("sourceB", BitSet.valueOf(new long[] {11, 13, 55})))));

    Map message = getMappedMessage(trace);

    List coverages = (List) message.get("coverages");
    assertEquals(2, message.get("version"));
    assertEquals(2, coverages.size());

    Map coverage0 = (Map) coverages.get(0);
    assertEquals(3L, ((Number) coverage0.get("span_id")).longValue());
    List files0 = (List) coverage0.get("files");
    assertByteArray(
        new int[] {2, 0, 0, 0, 0, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 0, 41},
        ((Map) files0.get(0)).get("bitmap"));

    Map coverage1 = (Map) coverages.get(1);
    assertEquals(4L, ((Number) coverage1.get("span_id")).longValue());
    List files1 = (List) coverage1.get("files");
    assertByteArray(
        new int[] {11, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0, 0, 0, 0, 55},
        ((Map) files1.get(0)).get("bitmap"));
  }

  @Test
  void skipsSpansThatHaveNoReports() throws Exception {
    List<? extends CoreSpan<?>> trace =
        givenTrace(
            null,
            new TestReport(
                DDTraceId.from(1),
                2L,
                3,
                Collections.singletonList(
                    new TestReportFileEntry("source", BitSet.valueOf(new long[] {83, 25, 48})))),
            null);

    Map message = getMappedMessage(trace);

    List coverages = (List) message.get("coverages");
    assertEquals(2, message.get("version"));
    assertEquals(1, coverages.size());
    Map coverage = (Map) coverages.get(0);
    assertEquals(3L, ((Number) coverage.get("span_id")).longValue());
  }

  @Test
  void skipsEmptyReports() throws Exception {
    List<? extends CoreSpan<?>> trace =
        givenTrace(
            new TestReport(
                DDTraceId.from(1),
                2L,
                3,
                Collections.singletonList(
                    new TestReportFileEntry("source", BitSet.valueOf(new long[] {33, 53, 87})))),
            new TestReport(DDTraceId.from(1), 2L, 4, Collections.emptyList()));

    Map message = getMappedMessage(trace);

    List coverages = (List) message.get("coverages");
    assertEquals(2, message.get("version"));
    assertEquals(1, coverages.size());
    assertEquals(3L, ((Number) ((Map) coverages.get(0)).get("span_id")).longValue());
  }

  @Test
  void skipsDuplicateReports() throws Exception {
    List<CoreSpan<?>> trace = new ArrayList<>();

    TestReport report =
        new TestReport(
            DDTraceId.from(1),
            2L,
            3,
            Collections.singletonList(
                new TestReportFileEntry("source", BitSet.valueOf(new long[] {3, 5, 8}))));

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

    Map message = getMappedMessage(trace);

    List coverages = (List) message.get("coverages");
    assertEquals(2, message.get("version"));
    assertEquals(1, coverages.size());
    assertEquals(3L, ((Number) ((Map) coverages.get(0)).get("span_id")).longValue());
  }

  private void assertByteArray(int[] expected, Object actual) {
    if (actual instanceof byte[]) {
      byte[] bytes = (byte[]) actual;
      assertEquals(expected.length, bytes.length);
      for (int i = 0; i < expected.length; i++) {
        assertEquals(expected[i], (int) bytes[i]);
      }
    } else {
      List actualList = (List) actual;
      assertEquals(expected.length, actualList.size());
      for (int i = 0; i < expected.length; i++) {
        assertEquals(expected[i], ((Number) actualList.get(i)).intValue());
      }
    }
  }

  private List<? extends CoreSpan<?>> givenTrace(TestReport... testReports) {
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

  private Map getMappedMessage(List<? extends CoreSpan<?>> trace) throws Exception {
    GrowableBuffer buffer = new GrowableBuffer(1024);
    CiTestCovMapperV2 mapper = new CiTestCovMapperV2(false);
    mapper.map(trace, new MsgPackWriter(buffer));

    ByteArrayWritableByteChannel channel = new ByteArrayWritableByteChannel();

    ByteBuffer slice = buffer.slice();
    datadog.trace.common.writer.Payload payload = mapper.newPayload().withBody(1, slice);
    payload.writeTo(channel);

    byte[] writtenBytes = channel.toByteArray();
    return objectMapper.readValue(writtenBytes, Map.class);
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
      byte[] buf = new byte[remaining];
      src.get(buf);
      outputStream.write(buf);
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
