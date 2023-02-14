package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.values.StringValue;

public class ContainsExpression extends StringPredicateExpression {
  public ContainsExpression(ValueExpression<?> sourceString, StringValue str) {
    super(sourceString, str, String::contains, "contains");
  }
}
