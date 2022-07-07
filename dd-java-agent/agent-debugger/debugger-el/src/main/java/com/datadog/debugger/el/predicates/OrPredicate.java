package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Predicate;

/** Performs logical OR */
public final class OrPredicate extends BinaryPredicate {
  public OrPredicate(Predicate left, Predicate right) {
    super(left, right);
  }

  @Override
  public boolean test() {
    return left.test() || right.test();
  }
}
