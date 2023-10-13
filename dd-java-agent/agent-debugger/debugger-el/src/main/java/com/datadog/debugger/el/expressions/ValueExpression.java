package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/**
 * A generic interface for expressions resolving to {@linkplain Value}
 *
 * @param <T>
 */
public interface ValueExpression<T extends Value<?>> extends Expression<T> {
  ValueExpression<?> NULL =
      new ValueExpression<Value<?>>() {
        @Override
        public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
          return Value.nullValue();
        }

        @Override
        public String toString() {
          return Value.nullValue().toString();
        }
      };

  ValueExpression<?> UNDEFINED =
      new ValueExpression<Value<?>>() {
        @Override
        public Value<?> evaluate(ValueReferenceResolver valueRefResolver) {
          return Value.undefinedValue();
        }

        @Override
        public String toString() {
          return Value.undefinedValue().toString();
        }
      };
}
