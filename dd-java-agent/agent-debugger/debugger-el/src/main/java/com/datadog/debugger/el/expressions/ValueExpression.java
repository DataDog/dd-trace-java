package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Value;

/**
 * A generic interface for expressions resolving to {@linkplain Value}
 *
 * @param <T>
 */
public interface ValueExpression<T extends Value<?>> extends Expression<T> {
  ValueExpression<?> NULL =
      new ValueExpression<Value<?>>() {
        @Override
        public Value<?> evaluate(EvalContext evalContext) {
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
        public Value<?> evaluate(EvalContext evalContext) {
          return Value.undefinedValue();
        }

        @Override
        public String toString() {
          return Value.undefinedValue().toString();
        }
      };
}
