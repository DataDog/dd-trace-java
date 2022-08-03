package com.datadog.profiling.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

final class ThreadSequences {
  private static final Logger log = LoggerFactory.getLogger(ThreadSequences.class);

  private final ThreadMap<LongSequence> sequenceMap;
  private final ThreadSequencesPool pool;
  volatile int ptr;

  ThreadSequences() {
    this(null, -1);
  }

  ThreadSequences(ThreadSequencesPool pool, int ptr) {
    this.sequenceMap = new ThreadMap<>();
    this.pool = pool;
    this.ptr = ptr;
  }

  void release() {
    sequenceMap.clear(LongSequence::release);
    if (pool != null) {
      pool.release(ptr);
    }
  }


  LongSequence get(long threadId) {
    return sequenceMap.get(threadId);
  }

  void put(long threadId, LongSequence sequence) {
    sequenceMap.put(threadId, sequence);
  }

  Set<LongMapEntry<LongSequence>> snapshot() {
    Set<LongMapEntry<LongSequence>> entrySet = new HashSet<>(64);
    sequenceMap.snapshot(entrySet::add);
    return entrySet;
  }
}
