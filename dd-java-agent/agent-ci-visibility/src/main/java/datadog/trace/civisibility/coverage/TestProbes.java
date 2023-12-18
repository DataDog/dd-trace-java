package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.source.Utils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProbes implements CoverageProbeStore {

  private static final Logger log = LoggerFactory.getLogger(TestProbes.class);

  private static final Map<String, Integer> totalProbeCounts = new HashMap<>();

  // Unbounded data structure that only exists within a single test span
  private final Map<Class<?>, ExecutionDataAdapter> probeActivations;
  private final Collection<String> nonCodeResources;
  private final SourcePathResolver sourcePathResolver;
  private volatile TestReport testReport;

  TestProbes(SourcePathResolver sourcePathResolver) {
    this.sourcePathResolver = sourcePathResolver;
    probeActivations = new ConcurrentHashMap<>();
    nonCodeResources = new ConcurrentLinkedQueue<>();
  }

  @Override
  public void record(Class<?> clazz) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void record(Class<?> clazz, long classId, int probeId) {
    probeActivations
        .computeIfAbsent(clazz, (ignored) -> new ExecutionDataAdapter(classId, clazz.getName()))
        .record(probeId);
  }

  @Override
  public void recordNonCodeResource(String absolutePath) {
    nonCodeResources.add(absolutePath);
  }

  @Override
  public void report(Long testSessionId, Long testSuiteId, long spanId) {
    Map<String, List<TestReportFileEntry.Segment>> segmentsBySourcePath = new HashMap<>();
    for (Map.Entry<Class<?>, ExecutionDataAdapter> e : probeActivations.entrySet()) {
      ExecutionDataAdapter executionDataAdapter = e.getValue();
      String className = executionDataAdapter.getClassName();
      Integer totalProbeCount = totalProbeCounts.get(className);

      if (totalProbeCount == null) {
        log.debug(
            "Skipping coverage reporting for {} because total probe count is absent", className);
        continue;
      }

      Class<?> clazz = e.getKey();
      String sourcePath = sourcePathResolver.getSourcePath(clazz);
      if (sourcePath == null) {
        log.debug(
            "Skipping coverage reporting for {} because source path could not be determined",
            className);
        continue;
      }

      try (InputStream is = Utils.getClassStream(clazz)) {
        List<TestReportFileEntry.Segment> segments =
            segmentsBySourcePath.computeIfAbsent(sourcePath, key -> new ArrayList<>());

        ExecutionDataStore store = new ExecutionDataStore();
        store.put(executionDataAdapter.toExecutionData(totalProbeCount));

        // TODO optimize this part to avoid parsing
        //  the same class multiple times for different test cases
        Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(segments));
        analyzer.analyzeClass(is, null);

      } catch (Exception exception) {
        log.debug(
            "Skipping coverage reporting for {} ({}) because of error",
            className,
            sourcePath,
            exception);
      }
    }

    List<TestReportFileEntry> fileEntries = new ArrayList<>(segmentsBySourcePath.size());
    for (Map.Entry<String, List<TestReportFileEntry.Segment>> e : segmentsBySourcePath.entrySet()) {
      String sourcePath = e.getKey();

      List<TestReportFileEntry.Segment> segments = e.getValue();
      segments.sort(Comparator.naturalOrder());

      List<TestReportFileEntry.Segment> compressedSegments = getCompressedSegments(segments);
      fileEntries.add(new TestReportFileEntry(sourcePath, compressedSegments));
    }

    for (String nonCodeResource : nonCodeResources) {
      String resourcePath = sourcePathResolver.getResourcePath(nonCodeResource);
      if (resourcePath == null) {
        log.debug(
            "Skipping coverage reporting for {} because resource path could not be determined",
            nonCodeResource);
        continue;
      }
      TestReportFileEntry fileEntry =
          new TestReportFileEntry(resourcePath, Collections.emptyList());
      fileEntries.add(fileEntry);
    }

    testReport = new TestReport(testSessionId, testSuiteId, spanId, fileEntries);
  }

  private static List<TestReportFileEntry.Segment> getCompressedSegments(
      List<TestReportFileEntry.Segment> segments) {
    List<TestReportFileEntry.Segment> compressedSegments = new ArrayList<>();

    int startLine = -1, endLine = -1;
    for (TestReportFileEntry.Segment segment : segments) {
      if (segment.getStartLine() <= endLine + 1) {
        endLine = Math.max(endLine, segment.getEndLine());
      } else {
        if (startLine > 0) {
          compressedSegments.add(new TestReportFileEntry.Segment(startLine, -1, endLine, -1, -1));
        }
        startLine = segment.getStartLine();
        endLine = segment.getEndLine();
      }
    }

    if (startLine > 0) {
      compressedSegments.add(new TestReportFileEntry.Segment(startLine, -1, endLine, -1, -1));
    }
    return compressedSegments;
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return testReport;
  }

  public static class TestProbesFactory implements CoverageProbeStoreFactory {

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      totalProbeCounts.put(className.replace('/', '.'), totalProbeCount);
    }

    @Override
    public CoverageProbeStore create(SourcePathResolver sourcePathResolver) {
      return new TestProbes(sourcePathResolver);
    }
  }
}
