package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** Check whether a {@linkplain Value} was resolved correctly or symbol exists.<br> */
public class IsDefinedExpression implements BooleanExpression {
  private final ValueExpression<?> valueExpression;

  public IsDefinedExpression(ValueExpression<?> valueExpression) {
    this.valueExpression = valueExpression;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    if (valueExpression == null) {
      return Boolean.FALSE;
    }
    try {
      Value<?> value = valueExpression.evaluate(valueRefResolver);
      return value.isUndefined() ? Boolean.FALSE : Boolean.TRUE;
    } catch (EvaluationException ex) {
      return Boolean.FALSE;
    }
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getValueExpression() {
    return valueExpression;
  }
}
