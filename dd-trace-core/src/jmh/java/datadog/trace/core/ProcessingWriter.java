package datadog.trace.core;

import datadog.trace.common.writer.Writer;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

/**
 * A {@link Writer} that does the serializer's tag work inline, without encoding: for each span it
 * runs {@link DDSpan#processTagsAndBaggage} (the lazy post-processor chain, and any pending derived
 * tags) and walks every tag. Comparing it with {@link DropWriter} separates the application
 * thread's share from the work a real serializer would do later.
 */
final class ProcessingWriter implements Writer {
  private final Blackhole blackhole;

  ProcessingWriter(Blackhole blackhole) {
    this.blackhole = blackhole;
  }

  @Override
  public void write(List<DDSpan> trace) {
    for (int i = 0; i < trace.size(); ++i) {
      trace
          .get(i)
          .processTagsAndBaggage(
              metadata ->
                  metadata
                      .getTags()
                      .forEach(blackhole, (bh, entry) -> bh.consume(entry.objectValue())),
              0);
    }
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
