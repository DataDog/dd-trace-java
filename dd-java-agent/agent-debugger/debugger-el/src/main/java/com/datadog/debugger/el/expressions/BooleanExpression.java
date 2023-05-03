package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Visitor;
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

        @Override
        public <R> R accept(Visitor<R> visitor) {
          return visitor.visit(this);
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

        @Override
        public <R> R accept(Visitor<R> visitor) {
          return visitor.visit(this);
        }
      };
}
