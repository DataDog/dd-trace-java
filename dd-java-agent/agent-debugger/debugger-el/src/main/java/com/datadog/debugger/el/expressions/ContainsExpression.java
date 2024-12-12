package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.CollectionValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

public class ContainsExpression implements BooleanExpression {
  private final ValueExpression<?> target;
  private final ValueExpression<?> value;

  public ContainsExpression(ValueExpression<?> target, ValueExpression<?> value) {
    this.target = target != null ? target : ValueExpression.NULL;
    this.value = value != null ? value : ValueExpression.NULL;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> targetValue = target.evaluate(valueRefResolver);
    if (targetValue.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (targetValue.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    Value<?> val = value.evaluate(valueRefResolver);
    if (val.isUndefined()) {
      return false;
    }
    if (targetValue.getValue() instanceof String) {
      String targetStr = (String) targetValue.getValue();
      if (val.getValue() instanceof String) {
        String valStr = (String) val.getValue();
        return targetStr.contains(valStr);
      }
      throw new EvaluationException(
          "Cannot evaluate the expression for non-string value", PrettyPrintVisitor.print(this));
    }
    if (targetValue instanceof CollectionValue) {
      try {
        return ((CollectionValue<?>) targetValue).contains(val);
      } catch (RuntimeException ex) {
        throw new EvaluationException(ex.getMessage(), PrettyPrintVisitor.print(this));
      }
    }
    return Boolean.FALSE;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getTarget() {
    return target;
  }

  public ValueExpression<?> getValue() {
    return value;
  }
}
