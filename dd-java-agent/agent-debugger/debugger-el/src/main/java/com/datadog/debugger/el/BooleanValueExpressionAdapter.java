package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.values.BooleanValue;

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
}
