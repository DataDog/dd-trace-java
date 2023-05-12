package datadog.trace.api.civisibility.coverage;

import java.util.Map;

public class TestReport {

  private final Long testSessionId;
  private final Long testSuiteId;
  private final long spanId;
  private final Map<String, TestReportFileEntry> testReportFileEntries;

  public TestReport(
      Long testSessionId,
      Long testSuiteId,
      long spanId,
      Map<String, TestReportFileEntry> testReportFileEntries) {
    this.testSessionId = testSessionId;
    this.testSuiteId = testSuiteId;
    this.spanId = spanId;
    this.testReportFileEntries = testReportFileEntries;
  }

  public Long getTestSessionId() {
    return testSessionId;
  }

  public Long getTestSuiteId() {
    return testSuiteId;
  }

  public long getSpanId() {
    return spanId;
  }

  public Map<String, TestReportFileEntry> getTestReportFileEntries() {
    return testReportFileEntries;
  }

  public boolean isNotEmpty() {
    return !testReportFileEntries.isEmpty();
  }
}
