package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** A generic interface for expressions resolving to {@linkplain Boolean} */
public interface PredicateExpression extends Expression<Boolean> {
  PredicateExpression TRUE =
      new PredicateExpression() {
        @Override
        public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
          return Boolean.TRUE;
        }

        @Override
        public String toString() {
          return "TRUE";
        }
      };
  PredicateExpression FALSE =
      new PredicateExpression() {
        @Override
        public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
          return Boolean.FALSE;
        }

        @Override
        public String toString() {
          return "FALSE";
        }
      };
}
