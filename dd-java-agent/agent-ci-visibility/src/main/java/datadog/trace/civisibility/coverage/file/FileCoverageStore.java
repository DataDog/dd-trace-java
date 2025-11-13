package datadog.trace.civisibility.coverage.file;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coverage probes with file-level granularity, reported coverage data only has the list of files,
 * no line info is available. The advantage of lower granularity is lower performance overhead.
 */
public class FileCoverageStore extends ConcurrentCoverageStore<FileProbes> {

  private static final Logger log = LoggerFactory.getLogger(FileCoverageStore.class);

  private final CiVisibilityMetricCollector metrics;
  private final SourcePathResolver sourcePathResolver;

  private FileCoverageStore(
      Function<Boolean, FileProbes> probesFactory,
      CiVisibilityMetricCollector metrics,
      SourcePathResolver sourcePathResolver) {
    super(probesFactory);
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
  }

  @Nullable
  @Override
  protected TestReport report(
      DDTraceId testSessionId, Long testSuiteId, long testSpanId, Collection<FileProbes> probes)
      throws SourceResolutionException {
    try {
      Set<Class<?>> combinedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
      Collection<String> combinedNonCodeResources = new HashSet<>();

      for (FileProbes probe : probes) {
        combinedClasses.addAll(probe.getCoveredClasses());
        combinedNonCodeResources.addAll(probe.getNonCodeResources());
      }

      if (combinedClasses.isEmpty() && combinedNonCodeResources.isEmpty()) {
        return null;
      }

      Set<String> coveredPaths = set(combinedClasses.size() + combinedNonCodeResources.size());
      for (Class<?> clazz : combinedClasses) {
        String sourcePath = sourcePathResolver.getSourcePath(clazz);
        if (sourcePath == null) {
          log.debug(
              "Skipping coverage reporting for {} because source path could not be determined",
              clazz);
          metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
          continue;
        }
        coveredPaths.add(sourcePath);
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
        coveredPaths.add(resourcePath);
      }

      List<TestReportFileEntry> fileEntries = new ArrayList<>(coveredPaths.size());
      for (String path : coveredPaths) {
        fileEntries.add(new TestReportFileEntry(path, null));
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

  private static <T> Set<T> set(int size) {
    return new HashSet<>(Math.max((int) (size / .75f) + 1, 16));
  }

  public static final class Factory implements CoverageStore.Factory {

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new FileCoverageStore(this::createProbes, metrics, sourcePathResolver);
    }

    private FileProbes createProbes(boolean isTestThread) {
      return new FileProbes(metrics, isTestThread);
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      // no op
    }
  }
}
