package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** A generic interface for expressions resolving to {@linkplain Boolean} */
public interface BooleanExpression extends Expression<Boolean> {
  BooleanExpression TRUE =
      new BooleanExpression() {
        @Override
        public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
          return Boolean.TRUE;
        }

        @Override
        public String toString() {
          return "true";
        }
      };
  BooleanExpression FALSE =
      new BooleanExpression() {
        @Override
        public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
          return Boolean.FALSE;
        }

        @Override
        public String toString() {
          return "false";
        }
      };
}
