package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

public class IndexExpression implements ValueExpression {

  private final ValueExpression<?> target;
  private final ValueExpression<?> key;

  public IndexExpression(ValueExpression<?> target, ValueExpression<?> key) {
    this.target = target;
    this.key = key;
  }

  @Override
  public Object evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> targetValue = target.evaluate(valueRefResolver);
    if (targetValue == Value.undefined()) {
      return targetValue;
    }
    Value<?> result = Value.undefinedValue();
    Value<?> keyValue = key.evaluate(valueRefResolver);
    if (keyValue == Value.undefined()) {
      return result;
    }
    if (targetValue instanceof MapValue) {
      result = ((MapValue) targetValue).get(keyValue.getValue());
    }
    if (targetValue instanceof ListValue) {
      result = ((ListValue) targetValue).get(keyValue.getValue());
    }
    return result;
  }
}
