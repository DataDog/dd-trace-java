package com.datadog.profiling.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class IntervalSequencePruner {
  private static final Logger log = LoggerFactory.getLogger(IntervalSequencePruner.class);

  private static final class PruningLongIterator implements LongIterator {
    private final LongIterator wrapped;
    private long cachedValue = 0L;

    PruningLongIterator(LongIterator wrapped) {
      this.wrapped = wrapped;
    }

    @Override
    public boolean hasNext() {
      if (cachedValue != 0) {
        // previously computed cached value available
        return true;
      }
      while (wrapped.hasNext()) {
        long val = wrapped.next();
        if (val != 0) {
          cachedValue = val;
          return true;
        }
      }
      return false;
    }

    @Override
    public long next() {
      try {
        return cachedValue;
      } finally {
        cachedValue = 0;
      }
    }
  }

  LongIterator pruneIntervals(LongSequence sequence, long timestamp) {
    int lastTransition = ProfilerTracingContextTracker.TRANSITION_NONE;
    int finishIndexStart = -1;
    int sequenceOffset = 0;
    LongIterator iterator = sequence.iterator();
    while (iterator.hasNext()) {
      long value = iterator.next();
      int transition = (int) ((value & ProfilerTracingContextTracker.TRANSITION_MASK) >>> 62);
      if (transition == ProfilerTracingContextTracker.TRANSITION_STARTED) {
        if (lastTransition == ProfilerTracingContextTracker.TRANSITION_STARTED) {
          // skip duplicated starts
          sequence.set(sequenceOffset, 0L);
        } else if (lastTransition > ProfilerTracingContextTracker.TRANSITION_STARTED) {
          if (finishIndexStart > -1) {
            int collapsedLength = sequenceOffset - finishIndexStart - 1;
            if (collapsedLength > 0) {
              for (int i = 0; i < collapsedLength; i++) {
                sequence.set(finishIndexStart + i, 0L);
              }
            }
            finishIndexStart = -1;
          }
          if (lastTransition == ProfilerTracingContextTracker.TRANSITION_MAYBE_FINISHED) {
            // skip 'maybe finished followed by started'
            sequence.set(sequenceOffset - 1, 0L);
            // also, ignore the start - instead just continue the previous interval
            sequence.set(sequenceOffset, 0L);
          }
        }
      } else if (transition == ProfilerTracingContextTracker.TRANSITION_MAYBE_FINISHED) {
        if (lastTransition == ProfilerTracingContextTracker.TRANSITION_NONE) {
          // dangling transition - remove it
          log.debug("Dangling 'maybe finished' transition");
          sequence.set(sequenceOffset, 0L);
          transition = lastTransition;
        } else if (lastTransition == ProfilerTracingContextTracker.TRANSITION_STARTED) {
          finishIndexStart = sequenceOffset;
        } else if (lastTransition == ProfilerTracingContextTracker.TRANSITION_FINISHED) {
          // 'finish' followed by 'maybe finished' turns into 'finished'
          transition = lastTransition;
        }
      } else if (transition == ProfilerTracingContextTracker.TRANSITION_FINISHED) {
        if (lastTransition == ProfilerTracingContextTracker.TRANSITION_NONE) {
          // dangling transition - remove it
          log.debug("Dangling 'finished' transition");
          sequence.set(sequenceOffset, 0L);
          transition = lastTransition;
        } else if (lastTransition == ProfilerTracingContextTracker.TRANSITION_STARTED) {
          // mark the start of 'finished' sequence
          finishIndexStart = sequenceOffset;
        }
      }
      lastTransition = transition;
      sequenceOffset++;
    }
    if (finishIndexStart > -1) {
      int maxIndex = sequence.size() - 1;
      for (int i = finishIndexStart; i < maxIndex; i++) {
        sequence.set(i, 0L);
      }
    }
    if (lastTransition == ProfilerTracingContextTracker.TRANSITION_STARTED) {
      // dangling start -> create a synthetic finished transition
      log.info("Dangling 'started' transition. Creating synthetic 'finished' transition.");
      sequence.add(ProfilerTracingContextTracker.maskDeactivation(timestamp, false));
    }
    return new PruningLongIterator(sequence.iterator());
  }
}
