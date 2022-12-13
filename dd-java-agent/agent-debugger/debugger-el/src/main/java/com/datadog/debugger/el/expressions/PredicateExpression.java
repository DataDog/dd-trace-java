package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Predicate;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** A generic interface for expressions resolving to {@linkplain Predicate} */
public interface PredicateExpression extends Expression<Predicate> {
  PredicateExpression TRUE = new LiteralBooleanExpression(Predicate.TRUE);
  PredicateExpression FALSE = new LiteralBooleanExpression(Predicate.FALSE);

  class LiteralBooleanExpression implements PredicateExpression {
    private final Predicate predicate;

    public LiteralBooleanExpression(Predicate predicate) {
      this.predicate = predicate;
    }

    @Override
    public Predicate evaluate(ValueReferenceResolver valueRefResolver) {
      return predicate;
    }

    @Override
    public <R> R accept(Visitor<R> visitor) {
      return visitor.visit(this);
    }

    public Predicate getPredicate() {
      return predicate;
    }
  }
}
