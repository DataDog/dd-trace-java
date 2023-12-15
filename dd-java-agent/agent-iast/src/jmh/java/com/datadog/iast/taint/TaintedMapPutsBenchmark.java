package com.datadog.iast.taint;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.datadog.iast.model.Range;
import datadog.trace.test.util.CircularBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

@Warmup(iterations = 3, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 5, time = 1000, timeUnit = MILLISECONDS)
@Timeout(time = 10000, timeUnit = MILLISECONDS)
@Fork(3)
@OutputTimeUnit(NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class TaintedMapPutsBenchmark {

  private static final int INITIAL_OP_COUNT = 1 << 12;
  private static final int OP_COUNT = 1024;

  private static final Range[] EMPTY_RANGES = new Range[0];

  private TaintedMap map;
  private List<Object> initialObjectList;
  private GarbageCollectorHandler gcHandler;

  @Setup(Level.Iteration)
  public void setup(BenchmarkParams params) {
    final boolean baseline = params.getBenchmark().endsWith("baseline");
    map = baseline ? TaintedMap.NoOp.INSTANCE : new TaintedMap.TaintedMapImpl();
    gcHandler = new GarbageCollectorHandler(OP_COUNT);
    initialObjectList = new ArrayList<>(INITIAL_OP_COUNT);
    for (int i = 0; i < INITIAL_OP_COUNT; i++) {
      final Object k = new Object();
      initialObjectList.add(k);
      map.put(new TaintedObject(k, EMPTY_RANGES));
    }
  }

  @Benchmark
  @OperationsPerInvocation(OP_COUNT)
  public void baseline() {
    for (int i = 0; i < OP_COUNT; i++) {
      final Object k = new Object();
      final TaintedObject to = new TaintedObject(k, EMPTY_RANGES);
      gcHandler.add(to);
      map.put(to);
    }
  }

  @Benchmark
  @OperationsPerInvocation(OP_COUNT)
  public void puts() {
    for (int i = 0; i < OP_COUNT; i++) {
      final Object k = new Object();
      final TaintedObject to = new TaintedObject(k, EMPTY_RANGES);
      gcHandler.add(to);
      map.put(to);
    }
  }

  /**
   * Reference queue that holds a circular buffer of alive objects and enqueues to be purged when
   * they are removed
   */
  private static class GarbageCollectorHandler {

    private final Map<Object, TaintedObject> map;
    private final CircularBuffer<Object> alive;

    public GarbageCollectorHandler(final int aliveCount) {
      map = new IdentityHashMap<>(aliveCount);
      alive = new CircularBuffer<>(aliveCount);
    }

    public void add(TaintedObject reference) {
      if (reference == null || reference.get() == null) {
        return;
      }
      final Object referent = reference.get();
      final Object toRemove = alive.add(referent);
      if (toRemove != null) {
        final TaintedObject taintedObject = map.remove(toRemove);
        taintedObject.enqueue();
      }
      map.put(reference.get(), reference);
    }
  }
}
