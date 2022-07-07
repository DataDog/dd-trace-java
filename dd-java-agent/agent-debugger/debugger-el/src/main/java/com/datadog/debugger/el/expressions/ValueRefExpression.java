package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Generated;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.util.Objects;

/** An expression taking a reference path and resolving to {@linkplain Value} */
@SuppressWarnings("rawtypes")
public final class ValueRefExpression implements ValueExpression {
  private final String path;

  public ValueRefExpression(String path) {
    if (ValueReferences.isRefExpression(path)) {
      this.path = path;
    } else {
      throw new IllegalArgumentException(
          "The provided path '" + path + "' does not constitute a value reference");
    }
  }

  @Override
  public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
    return Value.of(valueRefResolver.resolve(path));
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ValueRefExpression that = (ValueRefExpression) o;
    return Objects.equals(path, that.path);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(path);
  }

  @Generated
  @Override
  public String toString() {
    return "ValueRefExpression{" + "path='" + path + '\'' + '}';
  }
}
