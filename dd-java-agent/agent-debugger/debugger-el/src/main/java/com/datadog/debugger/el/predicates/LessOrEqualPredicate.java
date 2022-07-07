package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Value;
import java.util.Comparator;

/**
 * Compares two numeric {@linkplain Value} instances.
 *
 * @see java.util.Objects#compare(Object, Object, Comparator)
 */
public final class LessOrEqualPredicate extends NumericPredicate {
  public LessOrEqualPredicate(Value<?> left, Value<?> right) {
    super(left, right, LessOrEqualPredicate::operator);
  }

  private static boolean operator(Value<?> left, Value<?> right) {
    return compare(left, right) <= 0;
  }
}
