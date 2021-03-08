package datadog.trace.core;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
public class TraceCounters {

  long traces;
  long spans;
  long drops;

  public long traces() {
    return traces;
  }

  public long spans() {
    return spans;
  }

  public long drops() {
    return drops;
  }

  @Setup(Level.Iteration)
  public void reset() {
    traces = 0;
    spans = 0;
    drops = 0;
  }
}
