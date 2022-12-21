package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.util.Objects;

/** An expression taking a reference path and resolving to {@linkplain Value} */
@SuppressWarnings("rawtypes")
public final class ValueRefExpression implements ValueExpression {
  private final String symbolName;

  public ValueRefExpression(String symbolName) {
    this.symbolName = symbolName;
  }

  @Override
  public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
    return Value.of(valueRefResolver.lookup(symbolName));
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
}
