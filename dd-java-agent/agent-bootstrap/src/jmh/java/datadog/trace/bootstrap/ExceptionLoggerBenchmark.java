package datadog.trace.bootstrap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benchmark showing impact of ExceptionLogger
 *
 * <p>NOTE: This benchmark exists to check the efficiency of retrieving the ExceptionLogger.
 * Previously, this caused significant allocation.
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class ExceptionLoggerBenchmark {
  @Benchmark
  public Logger getExceptionLogger() {
    // This matches what happens in the bytecode weaving that defends against
    // exception leaking out of instrumentation.
    return LoggerFactory.getLogger(ExceptionLogger.class);
  }
}
