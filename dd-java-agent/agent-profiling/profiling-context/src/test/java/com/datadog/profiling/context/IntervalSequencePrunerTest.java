package com.datadog.profiling.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long stop = PerSpanTracingContextTracker.maskDeactivation(200, false);
    ls.add(start);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(200, true);
    ls.add(start);
    ls.add(maybeStop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, maybeStop), values);
  }

  @Test
  void testPruneMaybeStopWithStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(200, true);
    long stop = PerSpanTracingContextTracker.maskDeactivation(300, false);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStopWithRestartAndStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(200, true);
    long restart = PerSpanTracingContextTracker.maskActivation(300);
    long stop = PerSpanTracingContextTracker.maskDeactivation(400, false);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(restart);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStopWithRestartDangling() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(200, true);
    long restart = PerSpanTracingContextTracker.maskActivation(300);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(restart);

    long finishTs = 400;
    LongIterator iterator = instance.pruneIntervals(ls, finishTs);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    // the deactivation at 'finishTs' is added by the pruning not to have a dangling start
    assertEquals(
        Arrays.asList(start, PerSpanTracingContextTracker.maskDeactivation(finishTs, false)),
        values);
  }

  @Test
  void testPruneMaybeStopAfterStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long stop = PerSpanTracingContextTracker.maskDeactivation(200, false);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(300, true);

    ls.add(start);
    ls.add(stop);
    ls.add(maybeStop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, maybeStop), values);
  }

  @Test
  void testPruneStartAfterMultiStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long stop1 = PerSpanTracingContextTracker.maskDeactivation(200, false);
    long stop2 = PerSpanTracingContextTracker.maskDeactivation(300, false);
    long stop3 = PerSpanTracingContextTracker.maskDeactivation(400, false);
    long start1 = PerSpanTracingContextTracker.maskActivation(500);
    long stop4 = PerSpanTracingContextTracker.maskDeactivation(600, false);

    ls.add(start);
    ls.add(stop1);
    ls.add(stop2);
    ls.add(stop3);
    ls.add(start1);
    ls.add(stop4);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, stop3, start1, stop4), values);
  }

  @Test
  void testPruneMaybeStopAfterStopMulti() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long stop = PerSpanTracingContextTracker.maskDeactivation(200, false);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(300, true);
    long stop1 = PerSpanTracingContextTracker.maskDeactivation(400, false);
    long stop2 = PerSpanTracingContextTracker.maskDeactivation(500, false);

    ls.add(start);
    ls.add(stop);
    ls.add(maybeStop);
    ls.add(stop1);
    ls.add(stop2);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, stop2), values);
  }

  @Test
  void testPruneMultiStartStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long start1 = PerSpanTracingContextTracker.maskActivation(200);
    long stop = PerSpanTracingContextTracker.maskDeactivation(300, false);

    ls.add(start);
    ls.add(start1);
    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, stop), values);
  }

  @Test
  void testPruneMaybeStopMulti() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(200, true);
    long maybeStop2 = PerSpanTracingContextTracker.maskDeactivation(300, true);
    long maybeStop3 = PerSpanTracingContextTracker.maskDeactivation(400, true);

    ls.add(start);
    ls.add(maybeStop);
    ls.add(maybeStop2);
    ls.add(maybeStop3);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }
    assertFalse(iterator.hasNext());
    assertEquals(Arrays.asList(start, maybeStop3), values);
  }

  @Test
  void testPruneStopWithNoStart() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long stop = PerSpanTracingContextTracker.maskDeactivation(100, false);

    ls.add(stop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    assertFalse(iterator.hasNext());
  }

  @Test
  void testPruneMaybeStopWithNoStart() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long maybeStop = PerSpanTracingContextTracker.maskDeactivation(100, true);

    ls.add(maybeStop);

    LongIterator iterator = instance.pruneIntervals(ls, 0);
    assertFalse(iterator.hasNext());
  }

  @Test
  void testPruneStartWithNoStop() {
    LongSequence ls = new LongSequence(Allocators.heapAllocator(1024, 32));
    long start = PerSpanTracingContextTracker.maskActivation(100);
    long stopTs = 200;

    ls.add(start);

    LongIterator iterator = instance.pruneIntervals(ls, stopTs);
    List<Long> values = new ArrayList<>();
    while (iterator.hasNext()) {
      assertTrue(iterator.hasNext());
      values.add(iterator.next());
    }

    assertFalse(iterator.hasNext());
    assertEquals(
        Arrays.asList(start, PerSpanTracingContextTracker.maskDeactivation(stopTs, false)), values);
  }
}
