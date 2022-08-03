package com.datadog.profiling.context;

import com.datadog.profiling.context.allocator.Allocators;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadSequencesPoolTest {

  @Test
  void sanityTest() {
    ThreadSequencesPool pool = new ThreadSequencesPool(1, 2);

    ThreadSequences ts1 = pool.claim();
    assertNotNull(ts1);
    ThreadSequences ts2 = pool.claim();
    assertNotNull(ts2);
    ThreadSequences ts3 = pool.claim();
    assertNull(ts3);

    LongSequence ls = new LongSequence(Allocators.heapAllocator(128, 16));
    ts1.put(1, ls);

    ls.add(12356L);
    LongSequence ls1 = ts1.get(1);
    assertNotNull(ls1);
    assertTrue(ls1.getCapacity() > 0);

    ts1.snapshot();
    ts2.release();

    ls = ts1.get(1);

  }

}
