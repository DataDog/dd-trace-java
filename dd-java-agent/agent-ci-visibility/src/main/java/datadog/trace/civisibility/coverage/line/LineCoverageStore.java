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
import datadog.trace.civisibility.source.Utils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.jacoco.core.data.ExecutionData;
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
  private final ConcurrentHashMap<Long, BitSet[]> probeLineMappingCache;

  private LineCoverageStore(
      Function<Boolean, LineProbes> probesFactory,
      CiVisibilityMetricCollector metrics,
      SourcePathResolver sourcePathResolver,
      ConcurrentHashMap<Long, BitSet[]> probeLineMappingCache) {
    super(probesFactory);
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
    this.probeLineMappingCache = probeLineMappingCache;
  }

  @Nullable
  @Override
  protected TestReport report(
      DDTraceId testSessionId, Long testSuiteId, long testSpanId, Collection<LineProbes> probes) {
    Map<Class<?>, ExecutionDataAdapter> combinedExecutionData = new IdentityHashMap<>();
    Collection<String> combinedNonCodeResources = new HashSet<>();

    for (LineProbes probe : probes) {
      for (Map.Entry<Class<?>, ExecutionDataAdapter> e : probe.getExecutionData().entrySet()) {
        combinedExecutionData.merge(e.getKey(), e.getValue(), ExecutionDataAdapter::merge);
      }
      combinedNonCodeResources.addAll(probe.getNonCodeResources());
    }

    // Copy per-test probe data back into JaCoCo's shared $jacocoData arrays so that
    // JaCoCo's aggregate coverage reports remain accurate.
    copyProbeDataToJacoco(combinedExecutionData);

    if (combinedExecutionData.isEmpty() && combinedNonCodeResources.isEmpty()) {
      return null;
    }

    Map<String, BitSet> coveredLinesBySourcePath = new HashMap<>();
    for (Map.Entry<Class<?>, ExecutionDataAdapter> e : combinedExecutionData.entrySet()) {
      ExecutionDataAdapter executionDataAdapter = e.getValue();
      String className = executionDataAdapter.getClassName();

      Class<?> clazz = e.getKey();
      Collection<String> sourcePaths = sourcePathResolver.getSourcePaths(clazz);
      if (sourcePaths.size() != 1) {
        log.debug(
            "Skipping coverage reporting for {} because source path could not be determined",
            className);
        metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
        continue;
      }
      String sourcePath = sourcePaths.iterator().next();

      try {
        long classId = executionDataAdapter.getClassId();
        boolean[] probeActivations = executionDataAdapter.getProbeActivations();

        BitSet[] probeToLines =
            probeLineMappingCache.computeIfAbsent(
                classId,
                id -> {
                  try (InputStream is = Utils.getClassStream(clazz)) {
                    if (is == null) {
                      return new BitSet[0];
                    }
                    byte[] classBytes = readAllBytes(is);
                    return buildProbeLineMapping(
                        id, className, probeActivations.length, classBytes);
                  } catch (Exception ex) {
                    return new BitSet[0];
                  }
                });

        BitSet coveredLines =
            coveredLinesBySourcePath.computeIfAbsent(sourcePath, key -> new BitSet());
        for (int i = 0; i < probeActivations.length && i < probeToLines.length; i++) {
          if (probeActivations[i]) {
            coveredLines.or(probeToLines[i]);
          }
        }

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
      Collection<String> resourcePaths = sourcePathResolver.getResourcePaths(nonCodeResource);
      if (resourcePaths.isEmpty()) {
        log.debug(
            "Skipping coverage reporting for {} because resource path could not be determined",
            nonCodeResource);
        metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1, CoverageErrorType.PATH);
        continue;
      }
      for (String resourcePath : resourcePaths) {
        fileEntries.add(new TestReportFileEntry(resourcePath, null));
      }
    }

    TestReport report = new TestReport(testSessionId, testSuiteId, testSpanId, fileEntries);
    metrics.add(
        CiVisibilityDistributionMetric.CODE_COVERAGE_FILES,
        report.getTestReportFileEntries().size());
    return report;
  }

  private static byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[4096];
    int len;
    while ((len = is.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, len);
    }
    return buffer.toByteArray();
  }

  private static BitSet[] buildProbeLineMapping(
      long classId, String className, int probeCount, byte[] classBytes) {
    BitSet[] mapping = new BitSet[probeCount];
    for (int i = 0; i < probeCount; i++) {
      boolean[] singleProbe = new boolean[probeCount];
      singleProbe[i] = true;
      ExecutionDataStore store = new ExecutionDataStore();
      store.put(new ExecutionData(classId, className, singleProbe));
      BitSet lines = new BitSet();
      Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(lines));
      try {
        analyzer.analyzeClass(classBytes, null);
      } catch (Exception e) {
        // Analysis failure — empty BitSet is safe
      }
      mapping[i] = lines;
    }
    return mapping;
  }

  private static void copyProbeDataToJacoco(Map<Class<?>, ExecutionDataAdapter> executionData) {
    for (ExecutionDataAdapter adapter : executionData.values()) {
      boolean[] probeActivations = adapter.getProbeActivations();
      boolean[] jacocoData = adapter.getJacocoArray();
      if (jacocoData != null) {
        for (int i = 0; i < probeActivations.length && i < jacocoData.length; i++) {
          jacocoData[i] |= probeActivations[i];
        }
      }
    }
  }

  public static final class Factory implements CoverageStore.Factory {

    private final Map<String, Integer> probeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BitSet[]> probeLineMappingCache =
        new ConcurrentHashMap<>();

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new LineCoverageStore(
          this::createProbes, metrics, sourcePathResolver, probeLineMappingCache);
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
