package datadog.trace.api.config;

/** Constant with names of configuration options for production code coverage. */
public final class CodeCoverageConfig {

  public static final String CODE_COVERAGE_ENABLED = "code.coverage.enabled";
  public static final String CODE_COVERAGE_INCLUDES = "code.coverage.includes";
  public static final String CODE_COVERAGE_EXCLUDES = "code.coverage.excludes";
  public static final String CODE_COVERAGE_REPORT_INTERVAL_SECONDS =
      "code.coverage.report.interval.seconds";

  private CodeCoverageConfig() {}
}
