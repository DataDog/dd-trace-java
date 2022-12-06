package com.datadog.debugger.el.predicates;

import com.datadog.debugger.el.Predicate;

/** Combines two given predicates using a specific rule. */
public abstract class BinaryPredicate implements Predicate {
  /** Create a new {@linkplain BinaryPredicate} based on the given two predicates */
  public interface Combiner {
    BinaryPredicate get(Predicate left, Predicate right);
  }

  protected final Predicate left;
  protected final Predicate right;

  public BinaryPredicate(Predicate left, Predicate right) {
    this.left = left == null ? Predicate.FALSE : left;
    this.right = right == null ? Predicate.FALSE : right;
  }
}
