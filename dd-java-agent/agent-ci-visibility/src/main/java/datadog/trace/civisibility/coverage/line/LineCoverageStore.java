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

  /**
   * Upper bound on the number of cached class models. Coverage stays correct beyond it (the class
   * is just analyzed by Jacoco each time), this only guards memory for pathologically large suites.
   */
  private static final int MAX_MODEL_CACHE_ENTRIES = 50_000;

  private final CiVisibilityMetricCollector metrics;
  private final SourcePathResolver sourcePathResolver;
  // Module-wide, shared across tests: class id -> structural model, or the UNMODELLABLE sentinel
  // for
  // classes that must fall back to Jacoco. Building a model parses the class once; resolving a
  // test's covered lines against it is a cheap set intersection, so a class covered by many tests
  // is
  // parsed by Jacoco only once (on first encounter, to verify the model) rather than re-running
  // Jacoco's Analyzer per (test, class).
  private final Map<Long, ClassCoverageModel> modelCache;

  private LineCoverageStore(
      Function<Boolean, LineProbes> probesFactory,
      CiVisibilityMetricCollector metrics,
      SourcePathResolver sourcePathResolver,
      Map<Long, ClassCoverageModel> modelCache) {
    super(probesFactory);
    this.metrics = metrics;
    this.sourcePathResolver = sourcePathResolver;
    this.modelCache = modelCache;
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
   * Jacoco's {@link Analyzer} is the dominant cost of reporting, and the probe-to-line structure
   * depends only on the bytecode (not on which probes a test executed). So a class is parsed once
   * into a {@link ClassCoverageModel}; subsequent tests resolve their covered lines against the
   * model with a cheap set intersection.
   *
   * <p>Correctness of the model logic is established offline (see {@code
   * LineCoverageModelOracleTest}, which differential-tests it against Jacoco). As runtime
   * defense-in-depth, on first encounter the class is analyzed by Jacoco (authoritative, and the
   * result returned for that test) and the model checked against Jacoco for the empty, full, and
   * observed probe arrays; if it disagrees — or building it throws — the class is cached as {@link
   * ClassCoverageModel#UNMODELLABLE} and always analyzed by Jacoco thereafter. Model build/verify
   * failures never discard Jacoco's already-computed result. Note the battery is a sanity check,
   * not a proof of equality for every array — that assurance comes from the offline oracle.
   *
   * @return the covered lines, or {@code null} if the class could not be analyzed by Jacoco
   */
  @Nullable
  private BitSet analyzeClass(Class<?> clazz, ExecutionDataAdapter executionDataAdapter) {
    long classId = executionDataAdapter.getClassId();
    boolean[] probes = executionDataAdapter.getProbeActivations();

    ClassCoverageModel model = modelCache.get(classId);
    if (model != null && model != ClassCoverageModel.UNMODELLABLE) {
      return model.coveredLines(probes);
    }

    byte[] classBytes = readClassBytes(clazz);
    if (classBytes == null) {
      log.debug(
          "Skipping coverage reporting for {} because its bytecode is unavailable",
          executionDataAdapter.getClassName());
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
      return null;
    }

    // Jacoco is authoritative: compute (and return) its result regardless of what happens with the
    // model, so a model build/verify failure can never lose coverage Jacoco handled successfully.
    BitSet jacocoCoveredLines;
    try {
      jacocoCoveredLines =
          analyzeWithJacoco(classBytes, classId, executionDataAdapter.getClassName(), probes);
    } catch (Exception exception) {
      log.debug(
          "Skipping coverage reporting for {} because of error",
          executionDataAdapter.getClassName(),
          exception);
      metrics.add(CiVisibilityCountMetric.CODE_COVERAGE_ERRORS, 1);
      return null;
    }

    // First encounter (not the UNMODELLABLE sentinel): decide once, atomically, whether this class
    // gets a model. computeIfAbsent makes the decision happen exactly once per class id even under
    // concurrent reports, so a class can never end up both modelled and unmodellable.
    if (model == null && modelCache.size() < MAX_MODEL_CACHE_ENTRIES) {
      String className = executionDataAdapter.getClassName();
      modelCache.computeIfAbsent(
          classId, k -> decideModel(classBytes, classId, className, probes, jacocoCoveredLines));
    }
    return jacocoCoveredLines;
  }

  /**
   * Builds and verifies a model for a class; returns it if trustworthy, else {@link
   * ClassCoverageModel#UNMODELLABLE}. Runs under {@code computeIfAbsent}, so it executes once per
   * class id.
   */
  private static ClassCoverageModel decideModel(
      byte[] classBytes,
      long classId,
      String className,
      boolean[] observed,
      BitSet jacocoObserved) {
    try {
      ClassCoverageModel built = ClassCoverageModel.build(classBytes);
      if (modelMatchesJacoco(built, classBytes, classId, className, observed, jacocoObserved)) {
        return built;
      }
      log.debug(
          "Coverage model did not match Jacoco for {}, falling back to Jacoco analysis", className);
    } catch (Exception exception) {
      log.debug(
          "Could not build coverage model for {}, falling back to Jacoco analysis", className);
    }
    return ClassCoverageModel.UNMODELLABLE;
  }

  /**
   * Checks the model against Jacoco for the observed probe array plus the empty and full arrays.
   * The full array pins the exact set of coverable lines and the observed array a real case; this
   * is a cheap gross-error guard, complementing the exhaustive offline oracle.
   */
  private static boolean modelMatchesJacoco(
      ClassCoverageModel built,
      byte[] classBytes,
      long classId,
      String className,
      boolean[] observed,
      BitSet jacocoObserved) {
    try {
      if (!built.matches(observed, jacocoObserved)) {
        return false;
      }
      boolean[] all = new boolean[observed.length];
      java.util.Arrays.fill(all, true);
      if (!built.matches(all, analyzeWithJacoco(classBytes, classId, className, all))) {
        return false;
      }
      boolean[] none = new boolean[observed.length];
      return built.matches(none, analyzeWithJacoco(classBytes, classId, className, none));
    } catch (Exception e) {
      return false;
    }
  }

  /** Runs Jacoco's {@link Analyzer} for a class and probe array, returning the covered lines. */
  private static BitSet analyzeWithJacoco(
      byte[] classBytes, long classId, String className, boolean[] probes) throws IOException {
    BitSet coveredLines = new BitSet();
    ExecutionDataStore store = new ExecutionDataStore();
    store.put(new ExecutionData(classId, className, probes));
    Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(coveredLines));
    analyzer.analyzeClass(classBytes, className);
    return coveredLines;
  }

  @Nullable
  private static byte[] readClassBytes(Class<?> clazz) {
    try (InputStream is = Utils.getClassStream(clazz)) {
      if (is == null) {
        return null;
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = is.read(buf)) > 0) {
        bos.write(buf, 0, n);
      }
      return bos.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  public static final class Factory implements CoverageStore.Factory {

    private final Map<String, Integer> probeCounts = new ConcurrentHashMap<>();
    private final Map<Long, ClassCoverageModel> modelCache = new ConcurrentHashMap<>();

    private final CiVisibilityMetricCollector metrics;
    private final SourcePathResolver sourcePathResolver;

    public Factory(CiVisibilityMetricCollector metrics, SourcePathResolver sourcePathResolver) {
      this.metrics = metrics;
      this.sourcePathResolver = sourcePathResolver;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new LineCoverageStore(this::createProbes, metrics, sourcePathResolver, modelCache);
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
