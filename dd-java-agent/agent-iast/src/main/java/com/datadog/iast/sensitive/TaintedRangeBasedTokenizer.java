package com.datadog.iast.sensitive;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Range;
import com.datadog.iast.util.Ranged;
import java.util.NoSuchElementException;

public class TaintedRangeBasedTokenizer implements SensitiveHandler.Tokenizer {

  private final String value;
  private final Range[] ranges;

  private Ranged current;

  private int rangesIndex;

  private int pos;

  public TaintedRangeBasedTokenizer(final Evidence evidence) {
    this.ranges = evidence.getRanges();
    this.value = evidence.getValue();
    rangesIndex = 0;
    pos = 0; // current value position
  }

  @Override
  public boolean next() {
    current = buildNext();
    return current != null;
  }

  @Override
  public Ranged current() {
    if (current == null) {
      throw new NoSuchElementException();
    }
    return current;
  }

  private Ranged buildNext() {
    for (; rangesIndex < ranges.length; rangesIndex++) {
      Range range = ranges[rangesIndex];
      if (range.getStart() <= pos) {
        pos = range.getStart() + range.getLength();
      } else {
        Ranged next = Ranged.build(pos, range.getStart() - pos);
        pos = range.getStart() + range.getLength();
        return next;
      }
    }
    if (pos < value.length()) {
      Ranged next = Ranged.build(pos, value.length() - pos);
      pos = value.length();
      return next;
    }
    return null;
  }
}
