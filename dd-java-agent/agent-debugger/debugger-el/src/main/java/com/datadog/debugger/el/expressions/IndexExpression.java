package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.Redaction;

public class IndexExpression implements ValueExpression<Value<?>> {

  private final ValueExpression<?> target;
  private final ValueExpression<?> key;

  public IndexExpression(ValueExpression<?> target, ValueExpression<?> key) {
    this.target = target;
    this.key = key;
  }

  @Override
  public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> targetValue = target.evaluate(valueRefResolver);
    if (targetValue.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (targetValue.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    Value<?> result = Value.undefinedValue();
    Value<?> keyValue = key.evaluate(valueRefResolver);
    if (keyValue == Value.undefined()) {
      return result;
    }
    try {
      if (targetValue instanceof MapValue) {
        Object objKey = keyValue.getValue();
        if (objKey instanceof String && Redaction.isRedactedKeyword((String) objKey)) {
          ExpressionHelper.throwRedactedException(this);
        } else {
          result = ((MapValue) targetValue).get(objKey);
        }
      } else if (targetValue instanceof ListValue) {
        result = ((ListValue) targetValue).get(keyValue.getValue());
      } else {
        throw new EvaluationException(
            "Cannot evaluate the expression for unsupported type: "
                + targetValue.getClass().getTypeName(),
            PrettyPrintVisitor.print(this));
      }
    } catch (IllegalArgumentException ex) {
      throw new EvaluationException(ex.getMessage(), PrettyPrintVisitor.print(this), ex);
    }
    Object obj = result.getValue();
    if (obj != null && Redaction.isRedactedType(obj.getClass().getTypeName())) {
      ExpressionHelper.throwRedactedException(this);
    }
    return Value.of(result.getValue());
  }

  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getTarget() {
    return target;
  }

  public ValueExpression<?> getKey() {
    return key;
  }
}
