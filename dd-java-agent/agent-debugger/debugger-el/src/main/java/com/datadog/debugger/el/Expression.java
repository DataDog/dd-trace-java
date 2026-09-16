package com.datadog.debugger.el;

/** Represents any evaluable expression */
@FunctionalInterface
public interface Expression<ReturnType> {
  ReturnType evaluate(EvalContext evalContext);

  default <R> R accept(Visitor<R> visitor) {
    return null;
  }
}
