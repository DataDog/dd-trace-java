package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Value;
import java.util.Objects;

/** Compares two {@linkplain Value} instance for equality */
public final class EqualsPredicate extends ValuePredicate {
  public EqualsPredicate(Value<?> left, Value<?> right) {
    super(left, right, EqualsPredicate::operator);
  }

  private static boolean operator(Value<?> left, Value<?> right) {
    return Objects.equals(left.getValue(), right.getValue());
  }
}
