package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.ExpressionHelper.checkTimeout;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.Visitor;

/** Will negate the resolved {@linkplain BooleanExpression} */
public final class NotExpression implements BooleanExpression {
  private final BooleanExpression predicate;

  public NotExpression(BooleanExpression predicate) {
    this.predicate = predicate == null ? (BooleanExpression.FALSE) : predicate;
  }

  @Override
  public Boolean evaluate(EvalContext evalContext) {
    boolean result = !predicate.evaluate(evalContext);
    checkTimeout(evalContext.getTimeoutChecker(), this);
    return result;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public BooleanExpression getPredicate() {
    return predicate;
  }
}
