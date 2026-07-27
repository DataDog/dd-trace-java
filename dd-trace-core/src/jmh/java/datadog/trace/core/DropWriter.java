package datadog.trace.core;

import datadog.trace.common.writer.Writer;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Near-no-op {@link Writer}: drops finished traces (no serialization, no agent I/O, no {@link
 * TraceCounters} bookkeeping) so span-creation benchmarks measure only the application-thread
 * (front-half) allocation — create, tag, finish, PendingTrace completion. {@link #write} still
 * hands the trace to a {@link Blackhole} rather than truly doing nothing with it, so the JIT can't
 * treat the finish()-triggered write as dead code and eliminate work the real path performs.
 *
 * <p>Drift-stable: implements only the five-method {@link Writer} interface, unchanged
 * v1.53→master.
 */
final class DropWriter implements Writer {
  private final Blackhole blackhole;

  DropWriter(Blackhole blackhole) {
    this.blackhole = blackhole;
  }

  @Override
  public void write(List<DDSpan> trace) {
    blackhole.consume(trace);
  }

  @Override
  public void start() {}

  @Override
  public boolean flush() {
    return true;
  }

  @Override
  public void close() {}

  @Override
  public void incrementDropCounts(int spanCount) {}
}
