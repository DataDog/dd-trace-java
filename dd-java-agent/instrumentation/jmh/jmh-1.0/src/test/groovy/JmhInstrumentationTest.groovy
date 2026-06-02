import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.jmh.JmhUtils
import datadog.trace.instrumentation.jmh.benchmarks.SimpleBenchmark
import datadog.trace.instrumentation.jmh.benchmarks.ParameterizedBenchmark
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder

@DisableTestTrace(reason = "avoid self-tracing")
class JmhInstrumentationTest extends CiVisibilityInstrumentationTest {

  // Benchmark numeric metrics vary each run — they are verified structurally in the smoke test
  static final List<String> BENCHMARK_METRIC_TAGS = [
    "content.metrics.['benchmark.value']",
    "content.metrics.['benchmark.error']",
    "content.metrics.['benchmark.p50']",
    "content.metrics.['benchmark.p90']",
    "content.metrics.['benchmark.p95']",
    "content.metrics.['benchmark.p99']",
    "content.metrics.['benchmark.min']",
    "content.metrics.['benchmark.max']",
    "content.metrics.['benchmark.sample_count']",
  ]

  def "test #testcaseName"() {
    runBenchmarks(*benchmarkClasses)
    assertSpansData(testcaseName, [:], BENCHMARK_METRIC_TAGS)

    where:
    testcaseName                   | benchmarkClasses
    "test-benchmark-simple"        | [SimpleBenchmark]
    "test-benchmark-parameterized" | [ParameterizedBenchmark]
    // Multiple classes in one run keep several suites open at once (all finished in onRunEnd);
    // guards against suite spans being activated on the active-span stack, which would make
    // finishing them out of order throw IllegalStateException.
    "test-benchmark-multi-class"   | [SimpleBenchmark, ParameterizedBenchmark]
  }

  private void runBenchmarks(Class<?>... benchmarkClasses) {
    def builder = new OptionsBuilder()
      .jvmArgsAppend("-Djmh.ignoreLock=true")
    benchmarkClasses.each { builder.include(it.getName()) }
    new Runner(builder.build()).run()
  }

  @Override
  String instrumentedLibraryName() {
    "jmh"
  }

  @Override
  String instrumentedLibraryVersion() {
    JmhUtils.frameworkVersion()
  }
}
