package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.ExpressionHelper.checkTimeout;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.StringValue;

/**
 * Checks whether a {@linkplain Value} is empty.<br>
 * The result will be {@literal true} for empty string or collection, {@literal false} otherwise.
 */
public final class IsEmptyExpression implements BooleanExpression {
  private final ValueExpression<?> valueExpression;

  public IsEmptyExpression(ValueExpression<?> valueExpression) {
    this.valueExpression = valueExpression == null ? ValueExpression.NULL : valueExpression;
  }

  @Override
  public Boolean evaluate(EvalContext evalContext) {
    Value<?> value = valueExpression.evaluate(evalContext);
    if (value.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (value.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    boolean result = false;
    if (value instanceof CollectionValue) {
      result = ((CollectionValue<?>) value).isEmpty();
    } else if (value instanceof StringValue) {
      result = ((StringValue) value).isEmpty();
    }
    checkTimeout(evalContext.getTimeoutChecker(), this);
    return result;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getValueExpression() {
    return valueExpression;
  }
}
