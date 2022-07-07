package com.datadog.profiling.context;

/** A primitive long-value iterator */
public interface LongIterator {
  /** @return {@literal true} if the iterated seuqence has more elements */
  boolean hasNext();

  /**
   * @return the next long-value from the iterated sequence
   * @throws IllegalStateException if this method is called and there is no next value
   */
  long next();
}
