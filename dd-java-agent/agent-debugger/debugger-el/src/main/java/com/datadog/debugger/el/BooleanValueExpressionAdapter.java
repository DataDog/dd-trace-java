package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.values.BooleanValue;
import java.util.Objects;

public class BooleanValueExpressionAdapter implements ValueExpression<BooleanValue> {

  private final BooleanExpression booleanExpression;

  public BooleanValueExpressionAdapter(BooleanExpression booleanExpression) {
    this.booleanExpression = booleanExpression;
  }

  @Override
  public BooleanValue evaluate(EvalContext evalContext) {
    Boolean result = booleanExpression.evaluate(evalContext);
    if (result == null) {
      throw new EvaluationException(
          "Boolean expression returning null", PrettyPrintVisitor.print(this));
    }
    return new BooleanValue(result, ValueType.OBJECT);
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(booleanExpression);
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) return false;
    BooleanValueExpressionAdapter that = (BooleanValueExpressionAdapter) o;
    return Objects.equals(booleanExpression, that.booleanExpression);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hashCode(booleanExpression);
  }
}
