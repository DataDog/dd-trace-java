package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.CoverageWindow;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Line coverage of one test, collected by the probes that {@link ProbeClassTransformer} inserts.
 * The window opens when the store is created, taking over the window of the test's preparation (see
 * {@link TestPreparationWindow}), and closes when the test reports. The report is built later, on
 * the thread that serializes it.
 */
public final class ProbeCoverageStore extends CoverageWindow implements CoverageStore {

  private final CiVisibilityMetricCollector metrics;
  private final SourceFiles sourceFiles;

  // guards the fields below, which the test thread sets and the serializing thread reads
  private final Object reportLock = new Object();
  private List<ClassHits> hits;
  private DDTraceId testSessionId;
  private Long testSuiteId;
  private long testSpanId;
  private TestReport report;

  private ProbeCoverageStore(
      CiVisibilityMetricCollector metrics,
      SourceFiles sourceFiles,
      @Nullable CoverageWindow preparation) {
    super(preparation);
    this.metrics = metrics;
    this.sourceFiles = sourceFiles;
  }

  @Override
  public CoverageProbes getProbes() {
    return NoOpProbes.INSTANCE;
  }

  @Override
  public boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId) {
    // closing collects from other threads, so no lock is held meanwhile
    List<ClassHits> closedHits = close();
    synchronized (reportLock) {
      if (hits == null) {
        hits = closedHits;
        this.testSessionId = testSessionId;
        this.testSuiteId = testSuiteId;
        this.testSpanId = testSpanId;
      }
      return !hits.isEmpty();
    }
  }

  /**
   * A test that skipped itself ran code first; a test skipped before it started has an empty
   * window.
   */
  @Override
  public boolean reportsSkippedTests() {
    return true;
  }

  @Override
  public void discard() {
    List<ClassHits> closedHits = close();
    synchronized (reportLock) {
      if (hits == null) {
        hits = Collections.emptyList();
        ProbeRecorder.addSharedHits(closedHits);
      }
    }
  }

  @Nullable
  @Override
  public TestReport getReport() {
    synchronized (reportLock) {
      if (report == null && testSessionId != null && !hits.isEmpty()) {
        report = buildReport();
      }
      return report;
    }
  }

  private TestReport buildReport() {
    Map<String, BitSet> linesBySourcePath = new HashMap<>();
    // a class can have several entries
    BitSet classesNotInRepository = null;
    for (ClassHits classHits : hits) {
      // a test that runs code of a class is credited with the lines of its initializer
      boolean[] initProbes = ProbeRecorder.PROBES[classHits.classId];
      if (!sourceFiles.addLines(
          classHits.instrumentedClass, classHits.probes, initProbes, linesBySourcePath)) {
        if (classesNotInRepository == null) {
          classesNotInRepository = new BitSet();
        }
        classesNotInRepository.set(classHits.classId);
      }
    }
    if (classesNotInRepository != null) {
      metrics.add(
          CiVisibilityCountMetric.CODE_COVERAGE_ERRORS,
          classesNotInRepository.cardinality(),
          CoverageErrorType.PATH);
    }

    List<TestReportFileEntry> entries = new ArrayList<>(linesBySourcePath.size());
    for (Map.Entry<String, BitSet> entry : linesBySourcePath.entrySet()) {
      entries.add(new TestReportFileEntry(entry.getKey(), entry.getValue()));
    }
    metrics.add(CiVisibilityDistributionMetric.CODE_COVERAGE_FILES, entries.size());
    return new TestReport(testSessionId, testSuiteId, testSpanId, entries);
  }

  /** Creates one store per test. */
  public static final class Factory implements CoverageStore.Factory {

    private final CiVisibilityMetricCollector metrics;
    private final SourceFiles sourceFiles;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourceFiles = new SourceFiles(sourcePathResolver);
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new ProbeCoverageStore(metrics, sourceFiles, TestPreparationWindow.detach());
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      // probe counts are kept per class in ProbeRecorder
    }
  }
}
