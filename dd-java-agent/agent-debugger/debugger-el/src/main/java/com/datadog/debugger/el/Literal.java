package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.ValueExpression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Objects;

/** Represents any literal/constant in expression language */
public class Literal<ConstantType>
    implements Predicate, Value<ConstantType>, ValueExpression<Value<ConstantType>> {
  protected final ConstantType value;

  protected Literal(ConstantType value) {
    this.value = value;
  }

  @Override
  public boolean isNull() {
    return value == null || value == Values.NULL_OBJECT || value == Value.nullValue();
  }

  @Override
  public boolean isUndefined() {
    return value != null && (value == Values.UNDEFINED_OBJECT || value == Value.undefinedValue());
  }

  @Override
  public boolean test() {
    return !isNull();
  }

  @Override
  public Value<ConstantType> evaluate(ValueReferenceResolver valueRefResolver) {
    return this;
  }

  @Override
  public ConstantType getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Literal<?> literal = (Literal<?>) o;
    return Objects.equals(value, literal.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }
}
