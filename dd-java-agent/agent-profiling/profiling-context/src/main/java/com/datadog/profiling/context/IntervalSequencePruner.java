package com.datadog.profiling.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility class to 'prune' the interval sequence.<br>
 * An interval sequence is the result of a serie of activations and deactivations of a specific span
 * on a specific thread. All intervals present in the sequence are related to exactly one span and
 * thread and therefore it is possible to make assumptions about time linearity and do a simple
 * deduplication and simplification of the recorded raw data.
 */
final class IntervalSequencePruner {
  private static final Logger log = LoggerFactory.getLogger(IntervalSequencePruner.class);

  /**
   * All zero values should be removed from the sequence.<br>
   * {@literal 0} is used as a marker for pruned values. This possible thanks to the knowledge of
   * the pruned data - there will never be a valid zero value store before pruning.
   */
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

  /**
   * Perform deduplication and simplification of the original sequence raw data.
   *
   * <p>The deduplication and simplification rules are
   *
   * <ul>
   *   <li>All activations happening right after each other are 'collapsed' to the first one
   *   <li>All 'true' deactivations happening right after each other are 'collapsed' to the last one
   *   <li>All 'maybe' deactivations are
   *       <ul>
   *         <li>turned into 'true' deactivations if they follow 'true' deactivation
   *         <li>pruned if they are followed by an activate - the pruning constitues of removing
   *             both the deactivation and the following activation, effectively extending the
   *             original interval
   *       </ul>
   *       After the 'maybe' deactivation is turned into 'true' deactivation or pruned the other
   *       deduplication rules apply as necessary.
   * </ul>
   *
   * @param sequence the raw data sequence
   * @param timestampDelta the timestamp delta to use for the synthetic end transition if necessary
   * @return a {@linkplain LongIterator} instance providing access to the pruned data
   */
  LongIterator pruneIntervals(LongSequence sequence, long timestampDelta) {
    int lastTransition = PerSpanTracingContextTracker.TRANSITION_NONE;
    int finishIndexStart = -1;
    int sequenceOffset = 0;
    LongIterator iterator = sequence.iterator();
    while (iterator.hasNext()) {
      long value = iterator.next();
      int transition = (int) ((value & PerSpanTracingContextTracker.TRANSITION_MASK) >>> 62);
      if (transition == PerSpanTracingContextTracker.TRANSITION_STARTED) {
        if (lastTransition == PerSpanTracingContextTracker.TRANSITION_STARTED) {
          // skip duplicated starts
          sequence.set(sequenceOffset, 0L);
        } else if (lastTransition > PerSpanTracingContextTracker.TRANSITION_STARTED) {
          int collapsedLength = sequenceOffset - finishIndexStart - 1;
          if (collapsedLength > 0) {
            for (int i = 0; i < collapsedLength; i++) {
              sequence.set(finishIndexStart + i, 0L);
            }
          }
          finishIndexStart = -1;

          if (lastTransition == PerSpanTracingContextTracker.TRANSITION_MAYBE_FINISHED) {
            // skip 'maybe finished' followed by started
            sequence.set(sequenceOffset - 1, 0L);
            // also, ignore the start - instead just continue the previous interval
            sequence.set(sequenceOffset, 0L);
          }
        }
      } else if (transition == PerSpanTracingContextTracker.TRANSITION_MAYBE_FINISHED) {
        if (lastTransition == PerSpanTracingContextTracker.TRANSITION_NONE) {
          // dangling transition - remove it
          log.debug("Dangling 'maybe finished' transition");
          sequence.set(sequenceOffset, 0L);
          transition = lastTransition;
        } else if (lastTransition == PerSpanTracingContextTracker.TRANSITION_STARTED) {
          finishIndexStart = sequenceOffset;
        } else if (lastTransition == PerSpanTracingContextTracker.TRANSITION_FINISHED) {
          // 'finish' followed by 'maybe finished' turns into 'finished'
          transition = lastTransition;
        }
      } else { // if (transition == PerSpanTracingContextTracker.TRANSITION_FINISHED)
        if (lastTransition == PerSpanTracingContextTracker.TRANSITION_NONE) {
          // dangling transition - remove it
          log.debug("Dangling 'finished' transition");
          sequence.set(sequenceOffset, 0L);
          transition = lastTransition;
        } else if (lastTransition == PerSpanTracingContextTracker.TRANSITION_STARTED) {
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
    if (lastTransition == PerSpanTracingContextTracker.TRANSITION_STARTED) {
      // dangling start -> create a synthetic finished transition
      log.debug(
          "Dangling 'started' transition. Creating synthetic 'finished' transition @{} delta",
          timestampDelta);
      sequence.add(PerSpanTracingContextTracker.maskDeactivation(timestampDelta, false));
      sequence.adjustCapturedSize(8); // adjust the captured size
    }
    return new PruningLongIterator(sequence.iterator());
  }
}
