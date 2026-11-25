package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.CoverageWindow;
import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SuiteSetupWindow;
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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Line coverage of one test, collected by the probes that {@link ProbeClassTransformer} inserts.
 * The window opens when the store is created, taking over the window of the test's preparation (see
 * {@link TestPreparationWindow}), and closes when the test reports. The report adds the setup of
 * the suites the test runs in (see {@link SuiteSetupWindow}). It is built later, on the thread that
 * serializes it.
 */
public final class ProbeCoverageStore extends CoverageWindow implements CoverageStore {

  private static final Logger log = LoggerFactory.getLogger(ProbeCoverageStore.class);

  private static final AtomicBoolean WARNED_NO_SOURCE_FILE_FOUND = new AtomicBoolean();

  private final CiVisibilityMetricCollector metrics;
  private final SourceFiles sourceFiles;
  @Nullable private final SuiteSetupWindow.Hits suiteSetupHits;

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
      @Nullable TestPreparationWindow preparation) {
    super(preparation);
    this.metrics = metrics;
    this.sourceFiles = sourceFiles;
    this.suiteSetupHits = preparation != null ? preparation.suiteSetupHits() : null;
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
      return hasLines();
    }
  }

  /** Holds {@link #reportLock}. */
  private boolean hasLines() {
    // setup hits are linked only when they are not empty
    return !hits.isEmpty() || suiteSetupHits != null;
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
  public String getCollectorName() {
    return "probes";
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
      if (report == null && testSessionId != null && hasLines()) {
        report = buildReport();
      }
      return report;
    }
  }

  private TestReport buildReport() {
    Map<String, BitSet> linesBySourcePath = new HashMap<>();
    // a class can have several entries
    BitSet classesNotInRepository = new BitSet();
    addLines(hits, linesBySourcePath, classesNotInRepository);
    for (SuiteSetupWindow.Hits setup = suiteSetupHits; setup != null; setup = setup.outer) {
      addLines(setup.hits, linesBySourcePath, classesNotInRepository);
    }
    if (!classesNotInRepository.isEmpty()) {
      metrics.add(
          CiVisibilityCountMetric.CODE_COVERAGE_ERRORS,
          classesNotInRepository.cardinality(),
          CoverageErrorType.PATH);
      if (linesBySourcePath.isEmpty()) {
        warnNoSourceFileFound(classesNotInRepository.nextSetBit(0));
      }
    }

    List<TestReportFileEntry> entries = new ArrayList<>(linesBySourcePath.size());
    for (Map.Entry<String, BitSet> entry : linesBySourcePath.entrySet()) {
      entries.add(new TestReportFileEntry(entry.getKey(), entry.getValue()));
    }
    metrics.add(CiVisibilityDistributionMetric.CODE_COVERAGE_FILES, entries.size());
    return new TestReport(testSessionId, testSuiteId, testSpanId, entries);
  }

  /**
   * A test that ran code but has no line in any repository file points at a wrong repository root
   * or an empty repository index, which would otherwise leave every test without coverage,
   * silently.
   */
  private static void warnNoSourceFileFound(int classId) {
    if (!WARNED_NO_SOURCE_FILE_FOUND.compareAndSet(false, true)) {
      return;
    }
    InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
    log.warn(
        "Per-test line coverage could not find the source files of the classes a test ran, such as"
            + " {} ({}), among the repository's files, so the test reports no coverage. The"
            + " repository root may be wrong, or the repository could not be indexed. This is"
            + " logged once.",
        instrumentedClass != null ? instrumentedClass.name() : classId,
        instrumentedClass != null ? instrumentedClass.sourceFile() : null);
  }

  private void addLines(
      List<ClassHits> classHitsList,
      Map<String, BitSet> linesBySourcePath,
      BitSet classesNotInRepository) {
    for (ClassHits classHits : classHitsList) {
      // a test that runs code of a class is credited with the lines of its initializer
      boolean[] initProbes = ProbeRecorder.PROBES[classHits.classId];
      if (!sourceFiles.addLines(
          classHits.instrumentedClass, classHits.probes, initProbes, linesBySourcePath)) {
        classesNotInRepository.set(classHits.classId);
      }
    }
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
