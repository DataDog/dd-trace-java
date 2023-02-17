package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.StringValue;

public class MatchesExpression extends StringPredicateExpression {
  public MatchesExpression(ValueExpression<?> sourceString, StringValue str) {
    super(sourceString, str, String::matches, "matches");
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
