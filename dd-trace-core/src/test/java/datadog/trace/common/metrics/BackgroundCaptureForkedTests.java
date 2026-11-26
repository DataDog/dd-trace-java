package datadog.trace.common.metrics;

import datadog.trace.test.junit.utils.config.WithConfig;

/**
 * The aggregator test suites again, with the aggregator thread capturing each span's stats fields
 * instead of the finishing thread ({@link TraceCapture}). Each runs in its own JVM, since the flag
 * is read when an aggregator is built.
 */
final class BackgroundCaptureForkedTests {
  private BackgroundCaptureForkedTests() {}

  private static final String FLAG = "trace.experimental.stats.background.capture";

  @WithConfig(key = FLAG, value = "true")
  static final class ClientStatsAggregatorBackgroundCaptureForkedTest
      extends ClientStatsAggregatorTest {}

  @WithConfig(key = FLAG, value = "true")
  static final class InboxFullBackgroundCaptureForkedTest
      extends ClientStatsAggregatorInboxFullTest {}

  @WithConfig(key = FLAG, value = "true")
  static final class DisableBackgroundCaptureForkedTest extends ClientStatsAggregatorDisableTest {}

  @WithConfig(key = FLAG, value = "true")
  static final class OtlpExportBackgroundCaptureForkedTest
      extends ClientStatsAggregatorOtlpExportTest {}

  @WithConfig(key = FLAG, value = "true")
  static final class BootstrapBackgroundCaptureForkedTest
      extends ClientStatsAggregatorBootstrapTest {}
}
