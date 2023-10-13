package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.values.StringValue;

public class EndsWithExpression extends StringPredicateExpression {
  public EndsWithExpression(ValueExpression<?> sourceString, StringValue str) {
    super(sourceString, str, String::endsWith, "endsWith");
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }
}
