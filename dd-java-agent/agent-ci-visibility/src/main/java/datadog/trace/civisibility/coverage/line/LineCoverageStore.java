package datadog.trace.civisibility.coverage.line;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageErrorType;
import datadog.trace.civisibility.coverage.ConcurrentCoverageStore;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.SourceResolutionException;
import datadog.trace.civisibility.source.Utils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coverage probes with line-level granularity, reporting coverage data that contains specific lines
 * that are covered in a file.
 */
public class LineCoverageStore extends ConcurrentCoverageStore<LineProbes> {

  private static final Logger log = LoggerFactory.getLogger(LineCoverageStore.class);

  private final CiVisibilityMetricCollector metrics;
  private final SourcePathResolver sourcePathResolver;

  private LineCoverageStore(
      Function<Boolean, LineProbes> probesFactory,
      CiVisibilityMetricCollector metrics,
      SourcePathResolver sourcePathResolver) {
    super(probesFactory);
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
  }

  @Nullable
  @Override
  protected TestReport report(
      DDTraceId testSessionId, Long testSuiteId, long testSpanId, Collection<LineProbes> probes)
      throws SourceResolutionException {
    try {
      Map<Class<?>, ExecutionDataAdapter> combinedExecutionData = new IdentityHashMap<>();
      Collection<String> combinedNonCodeResources = new HashSet<>();

      for (LineProbes probe : probes) {
        for (Map.Entry<Class<?>, ExecutionDataAdapter> e : probe.getExecutionData().entrySet()) {
          combinedExecutionData.merge(e.getKey(), e.getValue(), ExecutionDataAdapter::merge);
        }
        combinedNonCodeResources.addAll(probe.getNonCodeResources());
      }

      if (combinedExecutionData.isEmpty() && combinedNonCodeResources.isEmpty()) {
        return null;
      }

      Map<String, BitSet> coveredLinesBySourcePath = new HashMap<>();
      for (Map.Entry<Class<?>, ExecutionDataAdapter> e : combinedExecutionData.entrySet()) {
        ExecutionDataAdapter executionDataAdapter = e.getValue();
        String className = executionDataAdapter.getClassName();

        Class<?> clazz = e.getKey();
        String sourcePath = sourcePathResolver.getSourcePath(clazz);
        if (sourcePath == null) {
          log.debug(
              "Skipping coverage reporting for {} because source path could not be determined",
              className);
          metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
          continue;
        }

        try (InputStream is = Utils.getClassStream(clazz)) {
          BitSet coveredLines =
              coveredLinesBySourcePath.computeIfAbsent(sourcePath, key -> new BitSet());
          ExecutionDataStore store = new ExecutionDataStore();
          store.put(executionDataAdapter.toExecutionData());

          // TODO optimize this part to avoid parsing
          //  the same class multiple times for different test cases
          Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(coveredLines));
          analyzer.analyzeClass(is, null);

        } catch (Exception exception) {
          log.debug(
              "Skipping coverage reporting for {} ({}) because of error",
              className,
              sourcePath,
              exception);
          metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
        }
      }

      List<TestReportFileEntry> fileEntries = new ArrayList<>(coveredLinesBySourcePath.size());
      for (Map.Entry<String, BitSet> e : coveredLinesBySourcePath.entrySet()) {
        String sourcePath = e.getKey();
        BitSet coveredLines = e.getValue();
        fileEntries.add(new TestReportFileEntry(sourcePath, coveredLines));
      }

      for (String nonCodeResource : combinedNonCodeResources) {
        String resourcePath = sourcePathResolver.getResourcePath(nonCodeResource);
        if (resourcePath == null) {
          log.debug(
              "Skipping coverage reporting for {} because resource path could not be determined",
              nonCodeResource);
          metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
          continue;
        }
        fileEntries.add(new TestReportFileEntry(resourcePath, null));
      }

      TestReport report = new TestReport(testSessionId, testSuiteId, testSpanId, fileEntries);
      metrics.add(
          CiVisibilityDistributionMetric.CODE_COVERAGE_FILES,
          report.getTestReportFileEntries().size());
      return report;

    } catch (Exception e) {
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
      throw e;
    }
  }

  public static final class Factory implements CoverageStore.Factory {

    private final Map<String, Integer> probeCounts = new ConcurrentHashMap<>();

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new LineCoverageStore(this::createProbes, metrics, sourcePathResolver);
    }

    private LineProbes createProbes(boolean isTestThread) {
      return new LineProbes(metrics, probeCounts, isTestThread);
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      probeCounts.put(className.replace('/', '.'), totalProbeCount);
    }
  }
}
