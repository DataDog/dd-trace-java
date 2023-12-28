package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.Redaction;
import java.util.Objects;

/** An expression taking a reference path and resolving to {@linkplain Value} */
public final class ValueRefExpression implements ValueExpression<Value<?>> {
  private final String symbolName;

  public ValueRefExpression(String symbolName) {
    this.symbolName = symbolName;
  }

  @Override
  public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
    Object symbol;
    try {
      symbol = valueRefResolver.lookup(symbolName);
    } catch (RuntimeException ex) {
      throw new EvaluationException(ex.getMessage(), PrettyPrintVisitor.print(this));
    }
    if (symbol != null) {
      String typeName = symbol.getClass().getTypeName();
      if (symbol == Redaction.REDACTED_VALUE || (Redaction.isRedactedType(typeName))) {
        ExpressionHelper.throwRedactedException(this);
      }
    }
    return Value.of(symbol);
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
