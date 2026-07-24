package datadog.trace.instrumentation.jmh;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.format.OutputFormat;

/**
 * Wraps a JMH {@link OutputFormat} to emit CI Visibility spans for each benchmark method.
 *
 * <p>Hooks only fire once per benchmark method (after all forks and iterations complete), so there
 * is zero overhead on the benchmark hot path.
 */
public class DDOutputFormat implements OutputFormat {

  private final OutputFormat delegate;

  public DDOutputFormat(OutputFormat delegate) {
    this.delegate = delegate;
  }

  @Override
  public void startBenchmark(BenchmarkParams benchParams) {
    delegate.startBenchmark(benchParams);
    DatadogJmhReporter.onBenchmarkStart(benchParams);
  }

  @Override
  public void endBenchmark(BenchmarkResult result) {
    try {
      DatadogJmhReporter.onBenchmarkEnd(result);
    } finally {
      delegate.endBenchmark(result);
    }
  }

  @Override
  public void endRun(java.util.Collection<RunResult> result) {
    try {
      DatadogJmhReporter.onRunEnd();
    } finally {
      delegate.endRun(result);
    }
  }

  // ---- Delegation-only methods ----

  @Override
  public void iteration(BenchmarkParams benchParams, IterationParams params, int iteration) {
    delegate.iteration(benchParams, params, iteration);
  }

  @Override
  public void iterationResult(
      BenchmarkParams benchParams, IterationParams params, int iteration, IterationResult data) {
    delegate.iterationResult(benchParams, params, iteration, data);
  }

  @Override
  public void startRun() {
    delegate.startRun();
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
