package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.Constants.CI_VISIBILITY_INSTRUMENTATION_NAME;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.CIConstants;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SharedPart;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.civisibility.domain.TestContextImpl;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports Shared: the lines that the process ran and that no test owns, as one record per part of
 * {@link SharedPart}. A record names its part in place of a span id. It is carried by a span that
 * only the coverage payload writes, and it is sent in a payload of its own, so that an intake that
 * rejects such records still takes the records of the tests.
 */
final class SharedCoverageReporter {

  private static final Logger log = LoggerFactory.getLogger(SharedCoverageReporter.class);

  private SharedCoverageReporter() {}

  static void report(SourceFiles sourceFiles, DDTraceId testSessionId) {
    try {
      List<TestReport> reports = buildReports(sourceFiles, testSessionId);
      if (reports.isEmpty()) {
        return;
      }
      AgentTracer.TracerAPI tracer = AgentTracer.get();
      // sends the records of the tests that ran so far
      tracer.flush();
      for (TestReport report : reports) {
        writeCarrierSpan(tracer, report);
      }
      // sends the Shared records before any later test adds its record to their payload
      tracer.flush();
    } catch (Throwable e) {
      log.warn("Could not report the coverage that no test owns", e);
    }
  }

  /** The reports of the parts of Shared that have lines in repository files. */
  static List<TestReport> buildReports(SourceFiles sourceFiles, DDTraceId testSessionId) {
    ProbeRecorder.flushShared();
    int classCount = ProbeRecorder.getClassCount();
    List<TestReport> reports = new ArrayList<>();
    BitSet classLines = new BitSet();
    for (SharedPart part : SharedPart.values()) {
      Map<String, BitSet> linesBySourcePath = new HashMap<>();
      for (int classId = 0; classId < classCount; classId++) {
        classLines.clear();
        if (ProbeRecorder.addSharedLines(part, classId, classLines)) {
          sourceFiles.addLines(
              ProbeRecorder.getInstrumentedClass(classId), classLines, linesBySourcePath);
        }
      }
      List<TestReportFileEntry> entries = new ArrayList<>(linesBySourcePath.size());
      for (Map.Entry<String, BitSet> entry : linesBySourcePath.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          entries.add(new TestReportFileEntry(entry.getKey(), entry.getValue()));
        }
      }
      if (!entries.isEmpty()) {
        reports.add(TestReport.shared(testSessionId, part, entries));
      }
    }
    return reports;
  }

  private static void writeCarrierSpan(AgentTracer.TracerAPI tracer, TestReport report) {
    AgentSpan span =
        tracer
            .buildSpan(CI_VISIBILITY_INSTRUMENTATION_NAME, "shared_coverage")
            .ignoreActiveSpan()
            .asChildOf(new TagContext(CIConstants.CIAPP_TEST_ORIGIN, null))
            .withRequestContextData(
                RequestContextSlot.CI_VISIBILITY, new TestContextImpl(new SharedReport(report)))
            .start();
    span.setSpanType(InternalSpanTypes.TEST_SHARED_COVERAGE);
    span.finish();
  }

  /** Gives the coverage payload the record that a carrier span carries. */
  private static final class SharedReport implements CoverageStore {

    private final TestReport report;

    SharedReport(TestReport report) {
      this.report = report;
    }

    @Override
    public CoverageProbes getProbes() {
      return NoOpProbes.INSTANCE;
    }

    @Override
    public boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId) {
      return true;
    }

    @Nullable
    @Override
    public TestReport getReport() {
      return report;
    }
  }
}
