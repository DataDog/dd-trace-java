package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Predicate;

public final class NotPredicate implements Predicate {
  private final Predicate predicate;

  public NotPredicate(Predicate predicate) {
    this.predicate = predicate == null ? Predicate.FALSE : predicate;
  }

  @Override
  public boolean test() {
    return !predicate.test();
  }
}
