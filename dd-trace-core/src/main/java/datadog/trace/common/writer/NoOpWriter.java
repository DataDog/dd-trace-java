package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import java.util.List;

/**
 * A writer that discards all traces. Used when the tracer is only installed to propagate context
 * (see {@code TraceInstrumentationConfig.PROPAGATE_CONTEXT}) and no trace data must be reported.
 */
public class NoOpWriter implements Writer {

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
  public void incrementDropCounts(int spanCount) {}

  @Override
  public String toString() {
    return "NoOpWriter { }";
  }
}
