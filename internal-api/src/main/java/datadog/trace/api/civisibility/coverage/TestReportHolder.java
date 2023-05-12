package datadog.trace.api.civisibility.coverage;

import javax.annotation.Nullable;

public interface TestReportHolder {
  @Nullable
  TestReport getReport();
}
