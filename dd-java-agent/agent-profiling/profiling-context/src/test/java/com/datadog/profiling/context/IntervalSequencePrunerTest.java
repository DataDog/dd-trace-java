package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadog.profiling.context.allocator.Allocators;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IntervalSequencePrunerTest {
  private IntervalSequencePruner instance;

  @BeforeEach
  void setup() {
    instance = new IntervalSequencePruner();
  }

  @Test
  void testPruneStartStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long stop = ProfilerTracingContextTracker.maskDeactivation(200, false);
    ls.add(start);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(200, true);
    ls.add(start);
    ls.add(maybeStop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, maybeStop), values);
  }

  @Test
  void testPruneMaybeStopWithStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(200, true);
    long stop = ProfilerTracingContextTracker.maskDeactivation(300, false);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStopWithRestartAndStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(200, true);
    long restart = ProfilerTracingContextTracker.maskActivation(300);
    long stop = ProfilerTracingContextTracker.maskDeactivation(400, false);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(restart);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStopWithRestartDangling() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(200, true);
    long restart = ProfilerTracingContextTracker.maskActivation(300);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(restart);

    long finishTs = 400;
    LongIterator iterator = instance.pruneIntervals(ls, finishTs);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    // the deactivation at 'finishTs' is added by the pruning not to have a dangling start
    assertEquals(
        Arrays.asList(start, ProfilerTracingContextTracker.maskDeactivation(finishTs, false)),
        values);
  }

  @Test
  void testPruneMaybeStopAfterStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long stop = ProfilerTracingContextTracker.maskDeactivation(200, false);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(300, true);

    ls.add(start);
    ls.add(stop);
    ls.add(maybeStop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, maybeStop), values);
  }

  @Test
  void testPruneMaybeStopAfterStopMulti() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long stop = ProfilerTracingContextTracker.maskDeactivation(200, false);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(300, true);
    long stop1 = ProfilerTracingContextTracker.maskDeactivation(400, false);
    long stop2 = ProfilerTracingContextTracker.maskDeactivation(500, false);

    ls.add(start);
    ls.add(stop);
    ls.add(maybeStop);
    ls.add(stop1);
    ls.add(stop2);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, stop2), values);
  }

  @Test
  void testPruneMultiStartStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long start1 = ProfilerTracingContextTracker.maskActivation(200);
    long stop = ProfilerTracingContextTracker.maskDeactivation(300, false);

    ls.add(start);
    ls.add(start1);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStopMulti() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(200, true);
    long maybeStop2 = ProfilerTracingContextTracker.maskDeactivation(300, true);
    long maybeStop3 = ProfilerTracingContextTracker.maskDeactivation(400, true);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(maybeStop2);
    ls.add(maybeStop3);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(Arrays.asList(start, maybeStop3), values);
  }

  @Test
  void testPruneStopWithNoStart() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long stop = ProfilerTracingContextTracker.maskDeactivation(100, false);

    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    assertFalse(iterator.hasNext());
  }

  @Test
  void testPruneMaybeStopWithNoStart() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long maybeStop = ProfilerTracingContextTracker.maskDeactivation(100, true);

    ls.add(maybeStop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    assertFalse(iterator.hasNext());
  }

  @Test
  void testPruneStartWithNoStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = ProfilerTracingContextTracker.maskActivation(100);
    long stopTs = 200;

    ls.add(start);

    LongIterator iterator = instance.pruneIntervals(ls, stopTs);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      values.add(iterator.next());
    }
    assertEquals(
        Arrays.asList(start, ProfilerTracingContextTracker.maskDeactivation(stopTs, false)),
        values);
  }
}
