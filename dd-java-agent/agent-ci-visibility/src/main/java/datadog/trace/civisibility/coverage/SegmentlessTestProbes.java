package datadog.trace.civisibility.coverage;

import datadog.trace.api.civisibility.coverage.CoverageProbeStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SegmentlessTestProbes implements CoverageProbeStore {

  private static final Logger log = LoggerFactory.getLogger(SegmentlessTestProbes.class);

  // Unbounded data structure that only exists within a single test span
  private final Set<Class<?>> coveredClasses;
  private final SourcePathResolver sourcePathResolver;
  private volatile TestReport testReport;

  SegmentlessTestProbes(SourcePathResolver sourcePathResolver) {
    this.sourcePathResolver = sourcePathResolver;
    coveredClasses = ConcurrentHashMap.newKeySet();
  }

  @Override
  public void record(Class<?> clazz, long classId, String className, int probeId) {
    coveredClasses.add(clazz);
  }

  @Override
  public void report(Long testSessionId, Long testSuiteId, long spanId) {
    List<TestReportFileEntry> fileEntries = new ArrayList<>(coveredClasses.size());
    for (Class<?> clazz : coveredClasses) {
      String sourcePath = sourcePathResolver.getSourcePath(clazz);
      if (sourcePath == null) {
        log.debug(
            "Skipping coverage reporting for {} because source path could not be determined",
            clazz);
        continue;
      }

      TestReportFileEntry fileEntry = new TestReportFileEntry(sourcePath, Collections.emptyList());
      fileEntries.add(fileEntry);
    }
    testReport = new TestReport(testSessionId, testSuiteId, spanId, fileEntries);
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return testReport;
  }

  public static class SegmentlessTestProbesFactory implements CoverageProbeStoreFactory {

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      // ignore
    }

    @Override
    public CoverageProbeStore create(SourcePathResolver sourcePathResolver) {
      return new SegmentlessTestProbes(sourcePathResolver);
    }
  }
}
