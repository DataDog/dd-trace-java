package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvalContext;
import com.datadog.debugger.el.Value;

/** TODO: Primordial support for 'debugger watches' support */
public final class ThenExpression implements ValueExpression<Value<Void>> {
  @Override
  public Value<Void> evaluate(EvalContext evalContext) {
    // TODO: This can be used to implement 'add watch' functionality where the script can amend the
    // collected snapshot
    return null;
  }
}
