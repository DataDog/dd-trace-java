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
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.format.OutputFormat;
import org.openjdk.jmh.util.Statistics;

/**
 * Wraps a JMH {@link OutputFormat} to emit CI Visibility spans for each benchmark method.
 *
 * <p>Hooks only fire once per benchmark method (after all forks and iterations complete), so there
 * is zero overhead on the benchmark hot path.
 */
public class DDOutputFormat implements OutputFormat {

  private final OutputFormat delegate;
  private final TestEventsHandler<String, String> handler;
  private final String frameworkVersion;

  // Keys used as suite/test descriptors in the handler — just the full benchmark name strings.
  // We keep suite and test keys separate so the handler can manage their lifetimes independently.
  private volatile String currentSuiteKey;
  private volatile String currentTestKey;

  public DDOutputFormat(OutputFormat delegate, String frameworkVersion) {
    this.delegate = delegate;
    this.frameworkVersion = frameworkVersion;
    this.handler =
        InstrumentationBridge.createTestEventsHandler(
            JmhUtils.FRAMEWORK_NAME, null, null, Collections.emptyList());
  }

  @Override
  public void startBenchmark(BenchmarkParams benchParams) {
    delegate.startBenchmark(benchParams);

    String fullName = benchParams.getBenchmark();
    String[] parts = JmhUtils.splitBenchmarkName(fullName);
    String suiteName = parts[0];
    String testName = parts[1];
    String testParameters = JmhUtils.testParameters(fullName);

    currentSuiteKey = suiteName + "#" + fullName;
    currentTestKey = fullName;

    handler.onTestSuiteStart(
        currentSuiteKey,
        suiteName,
        JmhUtils.FRAMEWORK_NAME,
        frameworkVersion,
        null,
        Collections.emptyList(),
        false,
        TestFrameworkInstrumentation.JMH,
        null);

    handler.onTestStart(
        currentSuiteKey,
        currentTestKey,
        testName,
        JmhUtils.FRAMEWORK_NAME,
        frameworkVersion,
        testParameters,
        Collections.emptyList(),
        TestSourceData.UNKNOWN,
        null,
        null);
  }

  @Override
  public void endBenchmark(BenchmarkResult result) {
    String suiteKey = currentSuiteKey;
    String testKey = currentTestKey;

    tagBenchmarkMetrics(result);

    handler.onTestFinish(testKey, null, null);
    handler.onTestSuiteFinish(suiteKey, null);

    delegate.endBenchmark(result);
  }

  private void tagBenchmarkMetrics(BenchmarkResult result) {
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

    Statistics stats = primary.getStatistics();
    if (stats.getN() > 1) {
      span.setMetric(BENCHMARK_P50, stats.getPercentile(50));
      span.setMetric(BENCHMARK_P90, stats.getPercentile(90));
      span.setMetric(BENCHMARK_P95, stats.getPercentile(95));
      span.setMetric(BENCHMARK_P99, stats.getPercentile(99));
      span.setMetric(BENCHMARK_MIN, stats.getMin());
      span.setMetric(BENCHMARK_MAX, stats.getMax());
      span.setMetric(BENCHMARK_SAMPLE_COUNT, stats.getN());
    }
  }

  // ---- Delegation-only methods ----

  @Override
  public void iteration(
      BenchmarkParams benchParams, org.openjdk.jmh.infra.IterationParams params, int iteration) {
    delegate.iteration(benchParams, params, iteration);
  }

  @Override
  public void iterationResult(
      BenchmarkParams benchParams,
      org.openjdk.jmh.infra.IterationParams params,
      int iteration,
      org.openjdk.jmh.results.IterationResult data) {
    delegate.iterationResult(benchParams, params, iteration, data);
  }

  @Override
  public void startRun() {
    delegate.startRun();
  }

  @Override
  public void endRun(java.util.Collection<RunResult> result) {
    handler.close();
    delegate.endRun(result);
  }

  @Override
  public void print(String s) {
    delegate.print(s);
  }

  @Override
  public void println(String s) {
    delegate.println(s);
  }

  @Override
  public void verbosePrintln(String s) {
    delegate.verbosePrintln(s);
  }

  @Override
  public void write(int b) {
    delegate.write(b);
  }

  @Override
  public void write(byte[] b) throws java.io.IOException {
    delegate.write(b);
  }

  @Override
  public void flush() {
    delegate.flush();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
