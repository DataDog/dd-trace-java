package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.CoverageWindow;
import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Line coverage of one test, collected by the probes that {@link ProbeClassTransformer} inserts.
 * The window opens when the store is created and closes when the test reports; the report is built
 * later, on the thread that serializes it.
 */
public final class ProbeCoverageStore extends CoverageWindow implements CoverageStore {

  private static final Logger log = LoggerFactory.getLogger(ProbeCoverageStore.class);

  /** Cached in {@link InstrumentedClass#setResolved} for classes without a single source path. */
  private static final String NO_SOURCE_PATH = "";

  private final CiVisibilityMetricCollector metrics;
  private final SourcePathResolver sourcePathResolver;

  // guards the fields below, which the test thread sets and the serializing thread reads
  private final Object reportLock = new Object();
  private List<ClassHits> hits;
  private DDTraceId testSessionId;
  private Long testSuiteId;
  private long testSpanId;
  private TestReport report;

  private ProbeCoverageStore(
      CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
  }

  @Override
  public CoverageProbes getProbes() {
    return NoOpProbes.INSTANCE;
  }

  @Override
  public boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId) {
    // closing waits for other threads still marking probes, so no lock is held meanwhile
    List<ClassHits> closedHits = isClosed() ? Collections.emptyList() : close();
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

  @Override
  public void discard() {
    if (!isClosed()) {
      close();
    }
    synchronized (reportLock) {
      if (hits == null) {
        hits = Collections.emptyList();
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
    for (ClassHits classHits : hits) {
      InstrumentedClass instrumentedClass = classHits.instrumentedClass;
      String sourcePath = sourcePath(instrumentedClass);
      if (sourcePath == null) {
        continue;
      }
      BitSet lines = linesBySourcePath.computeIfAbsent(sourcePath, path -> new BitSet());
      instrumentedClass.addLines(classHits.probes, lines);
      // a test that runs code of a class is credited with the lines of its initializer
      instrumentedClass.addInitLines(ProbeRecorder.PROBES[classHits.classId], lines);
    }

    List<TestReportFileEntry> entries = new ArrayList<>(linesBySourcePath.size());
    for (Map.Entry<String, BitSet> entry : linesBySourcePath.entrySet()) {
      entries.add(new TestReportFileEntry(entry.getKey(), entry.getValue()));
    }
    metrics.add(CiVisibilityDistributionMetric.CODE_COVERAGE_FILES, entries.size());
    return new TestReport(testSessionId, testSuiteId, testSpanId, entries);
  }

  @Nullable
  private String sourcePath(InstrumentedClass instrumentedClass) {
    Object resolved = instrumentedClass.getResolved();
    if (resolved == null) {
      resolved = resolveSourcePath(instrumentedClass);
      instrumentedClass.setResolved(resolved);
    }
    if (resolved == NO_SOURCE_PATH) {
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
      return null;
    }
    return (String) resolved;
  }

  private String resolveSourcePath(InstrumentedClass instrumentedClass) {
    ClassLoader loader = instrumentedClass.loader();
    if (loader == null) {
      return NO_SOURCE_PATH;
    }
    try {
      Class<?> clazz = Class.forName(instrumentedClass.name().replace('/', '.'), false, loader);
      Collection<String> sourcePaths = sourcePathResolver.getSourcePaths(clazz);
      if (sourcePaths.size() == 1) {
        return sourcePaths.iterator().next();
      }
    } catch (Throwable e) {
      log.debug("Could not resolve the source path of {}", instrumentedClass.name(), e);
    }
    log.debug(
        "Skipping coverage reporting for {} because source path could not be determined",
        instrumentedClass.name());
    return NO_SOURCE_PATH;
  }

  /** Creates one store per test. */
  public static final class Factory implements CoverageStore.Factory {

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new ProbeCoverageStore(metrics, sourcePathResolver);
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      // probe counts are kept per class in ProbeRecorder
    }
  }
}
