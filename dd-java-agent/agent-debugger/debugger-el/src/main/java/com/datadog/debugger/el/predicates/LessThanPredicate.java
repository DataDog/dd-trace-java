package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Value;
import java.util.Comparator;

/**
 * Compares two numeric {@linkplain Value} instances.
 *
 * @see java.util.Objects#compare(Object, Object, Comparator)
 */
public final class LessThanPredicate extends NumericPredicate {
  public LessThanPredicate(Value<?> left, Value<?> right) {
    super(left, right, LessThanPredicate::operator);
  }

  private static boolean operator(Value<?> left, Value<?> right) {
    return compare(left, right) < 0;
  }
}
