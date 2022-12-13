package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Predicate;

/** Performs logical AND */
public final class AndPredicate extends BinaryPredicate {

  public static final BinaryPredicate.Combiner AND = AndPredicate::new;

  public AndPredicate(Predicate left, Predicate right) {
    super(left, right);
  }

  @Override
  public boolean test() {
    return left.test() && right.test();
  }
}
