package datadog.trace.api.sampling;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
public class SamplerCounters {

  long tests;
  long sampled;

  public long tests() {
    return tests;
  }

  public long sampled() {
    return sampled;
  }

  public double sampleRate() {
    return (double) sampled / tests;
  }

  @Setup(Level.Iteration)
  public void reset() {
    tests = 0;
    sampled = 0;
  }
}
