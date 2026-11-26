package datadog.trace.api.civisibility.coverage;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What instrumented code pays at method entry to get its probe array on a thread that is not the
 * primary one: with a window open on the thread, and with none, where the thread looks up its owner
 * from its context at every entry. No tracer is registered, so the context lookup finds no span.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class ProbeArrayBenchmark {

  private static final int CLASS_ID = registerClass();

  static {
    makeAnotherThreadPrimary();
  }

  @State(Scope.Thread)
  public static class WindowOpen {
    private BenchmarkWindow window;

    @Setup(Level.Trial)
    public void open() {
      window = new BenchmarkWindow();
    }

    @TearDown(Level.Trial)
    public void close() {
      window.close();
    }
  }

  @Benchmark
  public boolean[] withWindowOpen(WindowOpen windowOpen) {
    return ProbeRecorder.probes(CLASS_ID);
  }

  @Benchmark
  public boolean[] withNoWindow() {
    return ProbeRecorder.probes(CLASS_ID);
  }

  private static final class BenchmarkWindow extends CoverageWindow {}

  private static int registerClass() {
    int classId = ProbeRecorder.reserveClassId();
    int probes = 8;
    int[][] probeLines = new int[probes][];
    int[] probeParent = new int[probes];
    for (int probe = 0; probe < probes; probe++) {
      probeLines[probe] = new int[] {probe + 1};
      probeParent[probe] = -1;
    }
    ProbeRecorder.register(
        classId,
        new InstrumentedClass(
            "benchmark/Fixture",
            ProbeArrayBenchmark.class.getClassLoader(),
            "Fixture.java",
            null,
            probes,
            probeLines,
            probeParent,
            new int[0][],
            new int[0]));
    return classId;
  }

  /** The first thread that opens a window becomes primary and skips the path measured here. */
  private static void makeAnotherThreadPrimary() {
    Thread thread = new Thread(new OpenAndCloseWindow());
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A class rather than a lambda: a lambda's body is a method of the benchmark class, which the
   * thread could not run before that class's initialization ends, and the initialization waits for
   * the thread.
   */
  private static final class OpenAndCloseWindow implements Runnable {
    @Override
    public void run() {
      new BenchmarkWindow().close();
    }
  }
}
