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
import java.util.concurrent.atomic.AtomicLong;
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

  /**
   * Upper bound on the approximate memory retained by the analysis cache. Coverage stays correct
   * beyond it (analysis just isn't cached), this only guards memory for pathologically large
   * suites. Bounding by bytes (rather than entry count) is what keeps a class with many probes,
   * covered by many distinct probe sets, from retaining large arrays for the whole module lifetime.
   */
  private static final long MAX_ANALYSIS_CACHE_BYTES = 64L * 1024 * 1024;

  /**
   * Approximate fixed cost of one cache entry beyond its variable bit data: the {@link
   * AnalysisCacheKey} and both {@link BitSet} objects (with their {@code long[]} + array headers)
   * plus the {@code ConcurrentHashMap} node. Deliberately generous so small entries aren't
   * undercounted and the byte bound stays a real ceiling.
   */
  private static final int APPROX_ENTRY_OVERHEAD_BYTES = 160;

  private final CiVisibilityMetricCollector metrics;
  private final SourcePathResolver sourcePathResolver;
  // Module-wide cache: (class id + probe set) -> covered lines, shared across tests so a class
  // covered identically by many tests is parsed by Jacoco's Analyzer only once.
  private final Map<AnalysisCacheKey, BitSet> analysisCache;
  // Approximate bytes retained by analysisCache, so the cache is bounded by size, not entry count.
  private final AtomicLong analysisCacheBytes;

  private LineCoverageStore(
      Function<Boolean, LineProbes> probesFactory,
      CiVisibilityMetricCollector metrics,
      SourcePathResolver sourcePathResolver,
      Map<AnalysisCacheKey, BitSet> analysisCache,
      AtomicLong analysisCacheBytes) {
    super(probesFactory);
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
    this.analysisCache = analysisCache;
    this.analysisCacheBytes = analysisCacheBytes;
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

      BitSet coveredLines = analyzeClass(clazz, executionDataAdapter);
      if (coveredLines != null) {
        coveredLinesBySourcePath.computeIfAbsent(sourcePath, key -> new BitSet()).or(coveredLines);
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

  /**
   * Resolves the covered lines for a class given a test's probe activations. Parsing the class with
   * Jacoco's {@link Analyzer} is the dominant cost of reporting, and the result depends only on the
   * class bytecode and the probe set, so it is memoized: the same class covered identically by
   * different tests is parsed once.
   *
   * @return the covered lines, or {@code null} if the class could not be analyzed
   */
  @Nullable
  private BitSet analyzeClass(Class<?> clazz, ExecutionDataAdapter executionDataAdapter) {
    long classId = executionDataAdapter.getClassId();
    // Snapshot the activations once and use the same snapshot for both the cache key and the
    // analysis. The per-test array is mutable and a propagated/background thread may record a late
    // probe while report() runs; sharing one snapshot ensures the cached covered lines always match
    // the key's probe set, so a late activation can't poison the entry for later tests.
    boolean[] probes = executionDataAdapter.getProbeActivations().clone();
    AnalysisCacheKey key = new AnalysisCacheKey(classId, probes);
    BitSet cached = analysisCache.get(key);
    if (cached != null) {
      return cached;
    }

    try (InputStream is = Utils.getClassStream(clazz)) {
      BitSet coveredLines = new BitSet();
      ExecutionDataStore store = new ExecutionDataStore();
      store.put(new ExecutionData(classId, executionDataAdapter.getClassName(), probes));
      Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(coveredLines));
      analyzer.analyzeClass(is, null);

      // Reserve the entry's weight before inserting so concurrent inserts near the limit can't
      // collectively overshoot the bound; release the reservation if we exceed it or another thread
      // cached the class first.
      long entryBytes =
          APPROX_ENTRY_OVERHEAD_BYTES + key.packedBytes() + (coveredLines.size() >>> 3);
      if (analysisCacheBytes.addAndGet(entryBytes) <= MAX_ANALYSIS_CACHE_BYTES) {
        if (analysisCache.putIfAbsent(key, coveredLines) != null) {
          analysisCacheBytes.addAndGet(-entryBytes);
        }
      } else {
        analysisCacheBytes.addAndGet(-entryBytes);
      }
      return coveredLines;

    } catch (Exception exception) {
      log.debug(
          "Skipping coverage reporting for {} because of error",
          executionDataAdapter.getClassName(),
          exception);
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
      return null;
    }
  }

  /**
   * Cache key identifying a class (by Jacoco class id) covered by a specific set of probes. The
   * probe activations are bit-packed into a {@link BitSet} rather than retaining the test's full
   * {@code boolean[]} (1 byte/element), so a cached key uses ~8x less memory. Equality is exact:
   * two keys match iff the same class was covered by the same set of probe ids.
   */
  static final class AnalysisCacheKey {
    private final long classId;
    private final BitSet probes;
    private final int hash;

    AnalysisCacheKey(long classId, boolean[] probeActivations) {
      this.classId = classId;
      BitSet bits = new BitSet(probeActivations.length);
      for (int i = 0; i < probeActivations.length; i++) {
        if (probeActivations[i]) {
          bits.set(i);
        }
      }
      this.probes = bits;
      this.hash = 31 * Long.hashCode(classId) + bits.hashCode();
    }

    /** Bytes of the packed probe bits (the variable part of the retained key). */
    int packedBytes() {
      return probes.size() >>> 3;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AnalysisCacheKey)) {
        return false;
      }
      AnalysisCacheKey other = (AnalysisCacheKey) o;
      return classId == other.classId && hash == other.hash && probes.equals(other.probes);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  public static final class Factory implements CoverageStore.Factory {

    private final Map<String, Integer> probeCounts = new ConcurrentHashMap<>();
    private final Map<AnalysisCacheKey, BitSet> analysisCache = new ConcurrentHashMap<>();
    private final AtomicLong analysisCacheBytes = new AtomicLong();

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new LineCoverageStore(
          this::createProbes, metrics, sourcePathResolver, analysisCache, analysisCacheBytes);
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
