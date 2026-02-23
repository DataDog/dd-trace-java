package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedList;
import static com.datadog.debugger.el.expressions.CollectionExpressionHelper.checkSupportedMap;

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
          MapValue mapValue = (MapValue) targetValue;
          checkSupportedMap(mapValue, this);
          result = mapValue.get(objKey);
        }
      } else if (targetValue instanceof ListValue) {
        ListValue listValue = (ListValue) targetValue;
        checkSupportedList(listValue, this);
        result = listValue.get(keyValue.getValue());
      } else {
        throw new EvaluationException(
            "Cannot evaluate the expression for unsupported type: "
                + targetValue.getClass().getTypeName(),
            PrettyPrintVisitor.print(this));
      }
    } catch (IllegalArgumentException | UnsupportedOperationException ex) {
      throw new EvaluationException(ex.getMessage(), PrettyPrintVisitor.print(this), ex);
    }
    Object obj = result.getValue();
    if (obj != null && Redaction.isRedactedType(obj.getClass().getTypeName())) {
      ExpressionHelper.throwRedactedException(this);
    }
    return Value.of(result.getValue(), result.getType());
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
