package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Predicate;

/** Performs logical AND */
public final class AndPredicate extends BinaryPredicate {
  public AndPredicate(Predicate left, Predicate right) {
    super(left, right);
  }

  @Override
  public boolean test() {
    return left.test() && right.test();
  }
}
