package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.ExpressionHelper.checkStringLength;
import static com.datadog.debugger.el.expressions.ExpressionHelper.checkTimeout;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.CollectionValue;

public class ContainsExpression implements BooleanExpression {
  private final ValueExpression<?> target;
  private final ValueExpression<?> value;

  public ContainsExpression(ValueExpression<?> target, ValueExpression<?> value) {
    this.target = target != null ? target : ValueExpression.NULL;
    this.value = value != null ? value : ValueExpression.NULL;
  }

  @Override
  public Boolean evaluate(EvalContext evalContext) {
    Value<?> targetValue = target.evaluate(evalContext);
    if (targetValue.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (targetValue.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    Value<?> val = value.evaluate(evalContext);
    if (val.isUndefined()) {
      return false;
    }
    boolean result;
    if (targetValue.getValue() instanceof String) {
      String targetStr = (String) targetValue.getValue();
      if (val.getValue() instanceof String) {
        String valStr = (String) val.getValue();
        checkStringLength(valStr, this);
        result = targetStr.contains(valStr);
        checkTimeout(evalContext.getTimeoutChecker(), this);
        return result;
      }
      throw new EvaluationException(
          "Cannot evaluate the expression for non-string value", PrettyPrintVisitor.print(this));
    }
    if (targetValue instanceof CollectionValue) {
      try {
        result = ((CollectionValue<?>) targetValue).contains(val);
        checkTimeout(evalContext.getTimeoutChecker(), this);
        return result;
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
