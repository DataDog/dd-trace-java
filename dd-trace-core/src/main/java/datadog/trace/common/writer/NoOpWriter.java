package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import java.util.List;

/**
 * A writer that does nothing. Useful for measuring agent overhead without span
 * serialization or transport on the hot path. Selected via {@code dd.writer.type=NoOpWriter}.
 */
public final class NoOpWriter implements Writer {

  @Override
  public void write(final List<DDSpan> trace) {}

  @Override
  public void start() {}

  @Override
  public boolean flush() {
    return true;
  }

  @Override
  public void close() {}

  @Override
  public void incrementDropCounts(final int spanCount) {}

  @Override
  public String toString() {
    return "NoOpWriter { }";
  }
}
