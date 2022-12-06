package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;

/** TODO: Primordial support for 'debugger watches' support */
public final class ThenExpression implements ValueExpression<Value<Void>> {
  @Override
  public Value<Void> evaluate(ValueReferenceResolver valueRefResolver) {
    // TODO: This can be used to implement 'add watch' functionality where the script can amend the
    // collected snapshot
    return null;
  }
}
