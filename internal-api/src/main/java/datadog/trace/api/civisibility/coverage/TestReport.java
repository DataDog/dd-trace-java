package datadog.trace.api.civisibility.coverage;

import datadog.trace.api.DDTraceId;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class TestReport {

  private final DDTraceId testSessionId;
  private final Long testSuiteId;
  private final long spanId;
  private final Collection<TestReportFileEntry> testReportFileEntries;
  @Nullable private final SharedPart sharedPart;

  public TestReport(
      DDTraceId testSessionId,
      Long testSuiteId,
      long spanId,
      @Nonnull Collection<TestReportFileEntry> testReportFileEntries) {
    this(testSessionId, testSuiteId, spanId, testReportFileEntries, null);
  }

  private TestReport(
      DDTraceId testSessionId,
      Long testSuiteId,
      long spanId,
      @Nonnull Collection<TestReportFileEntry> testReportFileEntries,
      @Nullable SharedPart sharedPart) {
    this.testSessionId = testSessionId;
    this.testSuiteId = testSuiteId;
    this.spanId = spanId;
    this.testReportFileEntries = testReportFileEntries;
    this.sharedPart = sharedPart;
  }

  /** A report of a part of the session's Shared coverage, which belongs to no test. */
  public static TestReport shared(
      DDTraceId testSessionId,
      SharedPart sharedPart,
      @Nonnull Collection<TestReportFileEntry> testReportFileEntries) {
    return new TestReport(testSessionId, null, 0, testReportFileEntries, sharedPart);
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

  /** The part of Shared this report holds, or {@code null} for a test's report. */
  @Nullable
  public SharedPart getSharedPart() {
    return sharedPart;
  }

  public boolean isNotEmpty() {
    return !testReportFileEntries.isEmpty();
  }
}
