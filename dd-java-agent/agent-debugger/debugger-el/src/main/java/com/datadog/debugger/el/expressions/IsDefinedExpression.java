package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.ExpressionHelper.checkTimeout;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.EvaluationTimeOutException;
import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import java.util.Objects;

/** Check whether a {@linkplain Value} was resolved correctly or symbol exists.<br> */
public class IsDefinedExpression implements BooleanExpression {
  private final ValueExpression<?> valueExpression;

  public IsDefinedExpression(ValueExpression<?> valueExpression) {
    this.valueExpression = valueExpression;
  }

  @Override
  public Boolean evaluate(EvalContext evalContext) {
    if (valueExpression == null) {
      return Boolean.FALSE;
    }
    try {
      Value<?> value = valueExpression.evaluate(evalContext);
      return value.isUndefined() ? Boolean.FALSE : Boolean.TRUE;
    } catch (EvaluationTimeOutException ex) {
      throw ex;
    } catch (EvaluationException ex) {
      return Boolean.FALSE;
    } finally {
      checkTimeout(evalContext.getTimeoutChecker(), this);
    }
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getValueExpression() {
    return valueExpression;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    IsDefinedExpression that = (IsDefinedExpression) o;
    return Objects.equals(valueExpression, that.valueExpression);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hashCode(valueExpression);
  }
}
