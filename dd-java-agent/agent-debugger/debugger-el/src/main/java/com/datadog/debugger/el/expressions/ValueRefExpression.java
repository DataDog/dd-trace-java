package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.util.Objects;

/** An expression taking a reference path and resolving to {@linkplain Value} */
public final class ValueRefExpression implements ValueExpression<Value<?>> {
  private final String symbolName;

  public ValueRefExpression(String symbolName) {
    this.symbolName = symbolName;
  }

  @Override
  public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
    try {
      return Value.of(valueRefResolver.lookup(symbolName));
    } catch (RuntimeException ex) {
      throw new EvaluationException(ex.getMessage(), symbolName);
    }
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ValueRefExpression that = (ValueRefExpression) o;
    return Objects.equals(symbolName, that.symbolName);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(symbolName);
  }

  @Generated
  @Override
  public String toString() {
    return "ValueRefExpression{" + "symbolName='" + symbolName + '\'' + '}';
  }

  public String getSymbolName() {
    return symbolName;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
