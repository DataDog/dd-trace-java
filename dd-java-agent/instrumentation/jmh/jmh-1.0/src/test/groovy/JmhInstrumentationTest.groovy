import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
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
    runBenchmark(benchmarkClass)
    assertSpansData(testcaseName, [:], BENCHMARK_METRIC_TAGS)

    where:
    testcaseName                 | benchmarkClass
    "test-benchmark-simple"      | SimpleBenchmark
    "test-benchmark-parameterized" | ParameterizedBenchmark
  }

  private void runBenchmark(Class<?> benchmarkClass) {
    def options = new OptionsBuilder()
      .include(benchmarkClass.getName())
      .jvmArgsAppend("-Djmh.ignoreLock=true")
      .build()
    new Runner(options).run()
  }

  @Override
  String instrumentedLibraryName() {
    "jmh"
  }

  @Override
  String instrumentedLibraryVersion() {
    Runner.class.getPackage().getImplementationVersion()
  }
}
