package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Predicate;

/** A generic interface for expressions resolving to {@linkplain Predicate} */
public interface PredicateExpression extends Expression<Predicate> {
  PredicateExpression TRUE = ctx -> Predicate.TRUE;
  PredicateExpression FALSE = ctx -> Predicate.FALSE;
}
