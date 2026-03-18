package datadog.trace.core;

import datadog.trace.common.writer.Writer;
import java.util.List;
import org.openjdk.jmh.infra.Blackhole;

public class BlackholeWriter implements Writer {

  private final Blackhole blackhole;
  private final TraceCounters counters;
  private final int tokens;

  public BlackholeWriter(Blackhole blackhole, TraceCounters counters, int tokens) {
    this.blackhole = blackhole;
    this.counters = counters;
    this.tokens = tokens;
  }

  @Override
  public void write(List<DDSpan> trace) {
    Blackhole.consumeCPU(tokens);
    blackhole.consume(trace);
    counters.traces++;
    counters.spans += trace.size();
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
  public void incrementDropCounts(int spanCount) {
    counters.drops++;
  }
}
