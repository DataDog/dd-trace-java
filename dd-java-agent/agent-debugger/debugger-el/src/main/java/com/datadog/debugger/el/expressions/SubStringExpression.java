package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.Expression.nullSafePrettyPrint;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

public class SubStringExpression implements ValueExpression<Value<String>> {
  private final ValueExpression<?> source;
  private final int startIndex;
  private final int endIndex;

  public SubStringExpression(ValueExpression<?> source, int startIndex, int endIndex) {
    this.source = source;
    this.startIndex = startIndex;
    this.endIndex = endIndex;
  }

  @Override
  public Value<String> evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> sourceValue = source != null ? source.evaluate(valueRefResolver) : Value.nullValue();
    if (sourceValue.getValue() instanceof String) {
      String sourceStr = (String) sourceValue.getValue();
      try {
        return (Value<String>) Value.of(sourceStr.substring(startIndex, endIndex));
      } catch (StringIndexOutOfBoundsException ex) {
        valueRefResolver.addEvaluationError(prettyPrint(), ex.getMessage());
      }
    }
    return Value.undefined();
  }

  @Override
  public String prettyPrint() {
    return "substring(" + nullSafePrettyPrint(source) + ", " + startIndex + ", " + endIndex + ")";
  }
}
