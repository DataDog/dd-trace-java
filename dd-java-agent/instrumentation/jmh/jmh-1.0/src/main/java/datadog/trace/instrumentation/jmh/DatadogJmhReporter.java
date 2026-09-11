package datadog.trace.instrumentation.jmh;

import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_ERROR;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_FORKS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_ITERATIONS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_MAX;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_MIN;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_MODE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_P50;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_P90;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_P95;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_P99;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_SAMPLE_COUNT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_THREADS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_TIME_UNIT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_UNIT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_VALUE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.BENCHMARK_WARMUP_ITERATIONS;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.util.Statistics;

public final class DatadogJmhReporter {

  private static final String TEST_FRAMEWORK = "jmh";
  private static final String TEST_FRAMEWORK_VERSION = JmhUtils.frameworkVersion();

  private static volatile TestEventsHandler<TestSuiteDescriptor, TestDescriptor>
      TEST_EVENTS_HANDLER;

  // Suites opened so far in this run, keyed by suite (class) name. JMH has no class-level
  // start/finish callbacks, so we open a suite the first time we see a benchmark of that class,
  // keep it open while every benchmark of the class runs under it, and finish all suites together
  // in onRunEnd.
  private static final Map<String, TestSuiteDescriptor> OPEN_SUITES = new ConcurrentHashMap<>();

  // The single benchmark (test) currently in flight. startBenchmark/endBenchmark only ever fire in
  // the launcher JVM, strictly sequentially (forked JVMs never call them), so a single reference is
  // enough. If it is still set when the next benchmark starts (or the run ends) JMH skipped
  // endBenchmark for a failed benchmark, and we finish it as a failure rather than leaking it.
  private static volatile TestDescriptor currentTest;

  private DatadogJmhReporter() {}

  public static synchronized void start() {
    if (TEST_EVENTS_HANDLER == null) {
      TEST_EVENTS_HANDLER =
          InstrumentationBridge.createTestEventsHandler(
              TEST_FRAMEWORK, null, null, JmhUtils.CAPABILITIES);
    }
  }

  public static synchronized void stop() {
    if (TEST_EVENTS_HANDLER != null) {
      TEST_EVENTS_HANDLER.close();
      TEST_EVENTS_HANDLER = null;
    }
  }

  public static void onBenchmarkStart(BenchmarkParams benchParams) {
    // lazy load handler
    start();

    // A previous benchmark whose endBenchmark was skipped (JMH swallows benchmark errors when
    // fail-on-error is off) is still open. Finish it as a failure before starting the next one.
    finishOpenTest(true);

    String fullName = benchParams.getBenchmark();
    String[] parts = JmhUtils.splitBenchmarkName(fullName);
    String suiteName = parts[0];
    String testName = parts[1];
    String testParameters = JmhUtils.testParameters(benchParams);

    // JMH gives us only fully-qualified names, not Class objects, so testClass is null. The
    // TestDescriptor includes testParameters in its identity, so each @Param variant is distinct.
    TestSuiteDescriptor suite = new TestSuiteDescriptor(suiteName, null);
    TestDescriptor test = new TestDescriptor(suiteName, null, testName, testParameters, null);
    currentTest = test;

    // Open the suite the first time we see this class; later benchmarks of the class reuse it.
    if (OPEN_SUITES.putIfAbsent(suiteName, suite) == null) {
      // A run can have benchmarks from several classes, so multiple suites stay open at once and
      // are finished together in onRunEnd (in arbitrary map order). Mark them parallelized so the
      // suite spans are not pushed onto the active-span stack — otherwise finishing an outer suite
      // while an inner one is still active throws IllegalStateException (TestSuiteImpl.end). Test
      // spans still self-activate, so tagBenchmarkMetrics' activeSpan() is unaffected.
      boolean parallelized = true;
      TEST_EVENTS_HANDLER.onTestSuiteStart(
          suite,
          suiteName,
          TEST_FRAMEWORK,
          TEST_FRAMEWORK_VERSION,
          null,
          Collections.emptyList(),
          parallelized,
          TestFrameworkInstrumentation.JMH,
          null);
    }

    TEST_EVENTS_HANDLER.onTestStart(
        suite,
        test,
        testName,
        TEST_FRAMEWORK,
        TEST_FRAMEWORK_VERSION,
        testParameters,
        Collections.emptyList(),
        TestSourceData.UNKNOWN,
        null,
        null);
  }

  public static void onBenchmarkEnd(BenchmarkResult result) {
    if (currentTest != null) {
      tagBenchmarkMetrics(result);
      finishOpenTest(false);
    }
  }

  /**
   * Finishes the in-flight benchmark's test span, if any, and clears it so a spurious second call
   * is a no-op. When {@code failed} is true the test is marked failed first (the failure propagates
   * to its suite when both are finished), so a benchmark whose {@code endBenchmark} was skipped
   * surfaces as {@code test.status = fail} instead of being silently leaked. Suites are left open
   * and finished together in {@link #onRunEnd()}.
   */
  private static void finishOpenTest(boolean failed) {
    TestDescriptor test = currentTest;
    if (test == null) {
      return;
    }
    currentTest = null;

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler = TEST_EVENTS_HANDLER;
    if (handler == null) {
      return;
    }
    if (failed) {
      handler.onTestFailure(test, null);
    }
    handler.onTestFinish(test, null, null);
  }

  private static void tagBenchmarkMetrics(BenchmarkResult result) {
    AgentSpan span = AgentTracer.activeSpan();
    if (span == null) {
      return;
    }

    BenchmarkParams params = result.getParams();
    span.setTag(BENCHMARK_MODE, params.getMode().shortLabel());
    span.setTag(BENCHMARK_ITERATIONS, params.getMeasurement().getCount());
    span.setTag(BENCHMARK_WARMUP_ITERATIONS, params.getWarmup().getCount());
    span.setTag(BENCHMARK_FORKS, params.getForks());
    span.setTag(BENCHMARK_THREADS, params.getThreads());
    span.setTag(BENCHMARK_TIME_UNIT, params.getTimeUnit().name());

    Result<?> primary = result.getPrimaryResult();
    span.setMetric(BENCHMARK_VALUE, primary.getScore());
    span.setTag(BENCHMARK_UNIT, primary.getScoreUnit());

    double error = primary.getScoreError();
    if (!Double.isNaN(error)) {
      span.setMetric(BENCHMARK_ERROR, error);
    }

    // Single-shot mode has no per-invocation distribution: any spread is across forks, not
    // samples, so the percentiles would be misleading. Only emit them when there is a real
    // sample distribution.
    Statistics stats = primary.getStatistics();
    if (params.getMode() != Mode.SingleShotTime && stats.getN() > 1) {
      span.setMetric(BENCHMARK_P50, stats.getPercentile(50));
      span.setMetric(BENCHMARK_P90, stats.getPercentile(90));
      span.setMetric(BENCHMARK_P95, stats.getPercentile(95));
      span.setMetric(BENCHMARK_P99, stats.getPercentile(99));
      span.setMetric(BENCHMARK_MIN, stats.getMin());
      span.setMetric(BENCHMARK_MAX, stats.getMax());
      span.setMetric(BENCHMARK_SAMPLE_COUNT, stats.getN());
    }
  }

  public static void onRunEnd() {
    // Flush a test left open by a swallowed failure, finish every suite opened during the run (JMH
    // gives us no per-class finish callback), then close the session/module.
    //
    // Note: onRunEnd is only invoked on the normal path (Runner.runBenchmarks -> out.endRun). With
    // fail-on-error enabled (non-default) a benchmark exception aborts the run before endRun, so
    // the open suites/tests for that run are not flushed — same data loss as a JVM crash. The
    // common
    // path (fail-on-error off, the JMH default) always reaches endRun and closes cleanly.
    finishOpenTest(true);
    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler = TEST_EVENTS_HANDLER;
    if (handler != null) {
      for (TestSuiteDescriptor suite : OPEN_SUITES.values()) {
        handler.onTestSuiteFinish(suite, null);
      }
    }
    OPEN_SUITES.clear();
    stop();
  }
}
