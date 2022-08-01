package com.datadog.profiling.context;

import java.util.concurrent.ConcurrentLinkedDeque;
import org.jctools.maps.NonBlockingHashMapLong;

final class ThreadSequencesCache {
  private static final class Singleton {
    private static final ThreadSequencesCache INSTANCE = new ThreadSequencesCache();
  }

  private final ConcurrentLinkedDeque<NonBlockingHashMapLong<LongSequence>> instances;

  static ThreadSequencesCache instance() {
    return Singleton.INSTANCE;
  }

  private ThreadSequencesCache() {
    instances = new ConcurrentLinkedDeque<>();
    for (int i = 0; i < 128; i++) {
      instances.add(new NonBlockingHashMapLong<>(64, false));
    }
  }

  NonBlockingHashMapLong<LongSequence> reserve() {
    NonBlockingHashMapLong<LongSequence> map = instances.poll();
    if (map == null) {
      map = new NonBlockingHashMapLong<>(64, false);
      instances.offer(map);
    }
    return map;
  }

  void release(NonBlockingHashMapLong<LongSequence> map) {
    instances.offer(map);
  }
}
