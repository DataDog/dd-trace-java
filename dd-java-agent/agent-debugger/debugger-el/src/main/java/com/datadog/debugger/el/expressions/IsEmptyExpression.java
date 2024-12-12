package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.CollectionValue;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

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
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> value = valueExpression.evaluate(valueRefResolver);
    if (value.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (value.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    if (value instanceof CollectionValue) {
      return ((CollectionValue<?>) value).isEmpty();
    } else if (value instanceof StringValue) {
      return ((StringValue) value).isEmpty();
    }
    return Boolean.FALSE;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getValueExpression() {
    return valueExpression;
  }
}
