package com.datadog.debugger.el;

import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** Represents any evaluable expression */
@FunctionalInterface
public interface Expression<ReturnType> {
  ReturnType evaluate(ValueReferenceResolver valueRefResolver);

  default String prettyPrint() {
    return this.toString();
  }

  static String nullSafePrettyPrint(Expression expr) {
    return expr != null ? expr.prettyPrint() : "null";
  }
}
