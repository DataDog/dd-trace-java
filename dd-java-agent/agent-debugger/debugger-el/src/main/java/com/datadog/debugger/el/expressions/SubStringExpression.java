package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.Visitor;
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
    if (sourceValue.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (sourceValue.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    if (sourceValue.getValue() instanceof String) {
      String sourceStr = (String) sourceValue.getValue();
      return internalEvaluate(sourceStr);
    }
    return Value.undefined();
  }

  private Value<String> internalEvaluate(String sourceStr) {
    try {
      return (Value<String>) Value.of(sourceStr.substring(startIndex, endIndex), ValueType.OBJECT);
    } catch (StringIndexOutOfBoundsException ex) {
      throw new EvaluationException(ex.getMessage(), PrettyPrintVisitor.print(this), ex);
    }
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public ValueExpression<?> getSource() {
    return source;
  }

  public int getStartIndex() {
    return startIndex;
  }

  public int getEndIndex() {
    return endIndex;
  }
}
