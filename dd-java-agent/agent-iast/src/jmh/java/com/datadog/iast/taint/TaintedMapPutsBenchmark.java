package com.datadog.iast.taint;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.datadog.iast.model.Range;
import datadog.trace.test.util.CircularBuffer;
import java.util.ArrayList;
import java.util.List;
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
import org.openjdk.jmh.infra.Blackhole;

@Warmup(iterations = 1, time = 1000, timeUnit = MILLISECONDS)
@Measurement(iterations = 3, time = 1000, timeUnit = MILLISECONDS)
@Timeout(time = 10000, timeUnit = MILLISECONDS)
@Fork(3)
@OutputTimeUnit(NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class TaintedMapPutsBenchmark {

  private static final int INITIAL_OP_COUNT = TaintedMap.DEFAULT_FLAT_MODE_THRESHOLD;
  private static final int OP_COUNT = 1024;

  private static final Range[] EMPTY_RANGES = new Range[0];

  private TaintedMap map;
  private List<Object> initialObjectList;
  private CircularBuffer<Object> objectBuffer;

  @Setup(Level.Iteration)
  public void setup() {
    map = new TaintedMap();
    objectBuffer = new CircularBuffer<>(OP_COUNT);
    initialObjectList = new ArrayList<>(INITIAL_OP_COUNT);
    for (int i = 0; i < INITIAL_OP_COUNT; i++) {
      final Object k = new Object();
      initialObjectList.add(k);
      map.put(new TaintedObject(k, EMPTY_RANGES, map.getReferenceQueue()));
    }
  }

  @Benchmark
  @OperationsPerInvocation(OP_COUNT)
  public void putsBaseline(final Blackhole bh) {
    for (int i = 0; i < OP_COUNT; i++) {
      final Object k = new Object();
      objectBuffer.add(k);
      bh.consume(new TaintedObject(k, EMPTY_RANGES, map.getReferenceQueue()));
    }
  }

  @Benchmark
  @OperationsPerInvocation(OP_COUNT)
  public void puts() {
    for (int i = 0; i < OP_COUNT; i++) {
      final Object k = new Object();
      objectBuffer.add(k);
      map.put(new TaintedObject(k, EMPTY_RANGES, map.getReferenceQueue()));
    }
  }
}
