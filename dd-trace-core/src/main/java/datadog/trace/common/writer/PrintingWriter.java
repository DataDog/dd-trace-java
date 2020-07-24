package datadog.trace.common.writer;

import datadog.trace.core.DDSpan;
import java.io.PrintStream;
import java.util.List;

public class PrintingWriter extends JsonStringWriter {
  private final PrintStream printStream;

  public PrintingWriter(final PrintStream printStream, final boolean hexIds) {
    super(hexIds);
    this.printStream = printStream;
  }

  @Override
  protected void writeJson(final String json) {
    printStream.println(json);
  }

  @Override
  protected void writeException(final List<DDSpan> trace, final Exception e) {
    // do nothing
  }

  @Override
  public void start() {
    // do nothing
  }

  @Override
  public void close() {
    // do nothing
  }

  @Override
  public void incrementTraceCount() {
    // do nothing
  }
}
