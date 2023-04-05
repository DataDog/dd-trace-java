package datadog.trace.civisibility.coverage;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
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
  private final long testSuiteId;
  private final long spanId;
  private final List<ExecutionData> executionDataList;

  public TestReport(
      Long testSessionId, long testSuiteId, long spanId, List<ExecutionData> executionDataList) {
    this.testSessionId = testSessionId;
    this.testSuiteId = testSuiteId;
    this.spanId = spanId;
    this.executionDataList = executionDataList;
  }

  void generate() {
    ExecutionDataStore store = new ExecutionDataStore();
    executionDataList.forEach(store::put);
    Analyzer analyzer = new Analyzer(store, new SourceAnalyzer(this::factory));

    try {
      analyzer.analyzeAll(".", null);
      log();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void log() {
    for (TestReportFileEntry entry : testReportFileEntries.values()) {
      if (entry.hasSegments()) {
        log.debug(entry.toString());
      }
    }
  }

  TestReportFileEntry factory(String className) {
    return testReportFileEntries.computeIfAbsent(className, (ignored) -> new TestReportFileEntry());
  }
}
