package datadog.trace.civisibility.coverage;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestReport {
  private static final Logger log = LoggerFactory.getLogger(TestReport.class);

  private final Map<String, TestReportFileEntry> testReportFileEntries = new HashMap<>();

  private final Long testSessionId;
  private final long testModuleId;
  private final long testSuiteId;
  private final long spanId;

  public TestReport(Long testSessionId, long testModuleId, long testSuiteId, long spanId) {
    this.testSessionId = testSessionId;
    this.testModuleId = testModuleId;
    this.testSuiteId = testSuiteId;
    this.spanId = spanId;
  }

  void generate(InputStream is, String sourcePath, ExecutionData executionData) throws IOException {
    ExecutionDataStore store = new ExecutionDataStore();
    store.put(executionData);

    TestReportFileEntry fileEntry = factory(sourcePath);

    Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(fileEntry));
    analyzer.analyzeClass(is, null);
  }

  void log() {
    for (TestReportFileEntry entry : testReportFileEntries.values()) {
      if (entry.hasSegments()) {
        log.debug(entry.toString());
      }
    }
  }

  TestReportFileEntry factory(String sourcePath) {
    return testReportFileEntries.computeIfAbsent(sourcePath, TestReportFileEntry::new);
  }
}
