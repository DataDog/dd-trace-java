package com.datadog.debugger.el;

/** Represents any test predicate of the expression language */
@FunctionalInterface
public interface Predicate {
  Predicate TRUE = () -> true;
  Predicate FALSE = () -> false;

  boolean test();
}
