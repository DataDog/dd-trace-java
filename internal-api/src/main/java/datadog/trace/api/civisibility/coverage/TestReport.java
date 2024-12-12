package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.DDTraceId;
import java.util.Collection;
import javax.annotation.Nonnull;

public class TestReport {

  private final DDTraceId testSessionId;
  private final Long testSuiteId;
  private final long spanId;
  private final Collection<TestReportFileEntry> testReportFileEntries;

  public TestReport(
      DDTraceId testSessionId,
      Long testSuiteId,
      long spanId,
      @Nonnull Collection<TestReportFileEntry> testReportFileEntries) {
    this.testSessionId = testSessionId;
    this.testSuiteId = testSuiteId;
    this.spanId = spanId;
    this.testReportFileEntries = testReportFileEntries;
  }

  public DDTraceId getTestSessionId() {
    return testSessionId;
  }

  public Long getTestSuiteId() {
    return testSuiteId;
  }

  public long getSpanId() {
    return spanId;
  }

  @Nonnull
  public Collection<TestReportFileEntry> getTestReportFileEntries() {
    return testReportFileEntries;
  }

  public boolean isNotEmpty() {
    return !testReportFileEntries.isEmpty();
  }
}
