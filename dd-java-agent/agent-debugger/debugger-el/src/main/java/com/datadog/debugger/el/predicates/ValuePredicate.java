package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.Value;

/** A common superclass for predicates operating on values. */
public abstract class ValuePredicate implements Predicate {
  /** Create a new {@linkplain ValuePredicate} combining the provided two values */
  public interface Combiner {
    ValuePredicate get(Value<?> left, Value<?> right);
  }

  public interface Operator<T> {
    boolean apply(Value<?> left, Value<?> right);
  }

  protected final Value<?> left;
  protected final Value<?> right;
  private final Operator operator;

  public ValuePredicate(Value<?> left, Value<?> right, Operator operator) {
    this.left = left;
    this.right = right;
    this.operator = operator;
  }

  @SuppressWarnings("unchecked")
  @Override
  public final boolean test() {
    if (left.isUndefined() || right.isUndefined()) {
      return false;
    }
    return operator.apply(left, right);
  }
}
