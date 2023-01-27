package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.values.StringValue;

public class StartsWithExpression extends StringPredicateExpression {
  public StartsWithExpression(ValueExpression<?> sourceString, StringValue str) {
    super(sourceString, str, String::startsWith, "startsWith");
  }
}
