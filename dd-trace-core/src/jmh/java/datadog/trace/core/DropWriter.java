package datadog.trace.core;

import datadog.trace.common.writer.Writer;
import java.util.List;

/**
 * No-op {@link Writer}: drops finished traces so span-creation benchmarks measure only the
 * application-thread (front-half) allocation — create, tag, finish, PendingTrace completion — with
 * no serialization or agent I/O leaking into the {@code -prof gc} number.
 *
 * <p>Drift-stable: implements only the five-method {@link Writer} interface, unchanged
 * v1.53→master.
 */
final class DropWriter implements Writer {
  @Override
  public void write(List<DDSpan> trace) {}

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
