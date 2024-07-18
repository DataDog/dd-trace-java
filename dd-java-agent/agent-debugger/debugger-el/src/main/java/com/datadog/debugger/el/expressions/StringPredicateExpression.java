package com.datadog.debugger.el.expressions;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.PrettyPrintVisitor;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.StringValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.util.function.BiPredicate;

public class StringPredicateExpression implements BooleanExpression {
  private final ValueExpression<?> sourceString;
  private final StringValue str;
  private final BiPredicate<String, String> predicate;
  private final String name;

  public StringPredicateExpression(
      ValueExpression<?> sourceString,
      StringValue str,
      BiPredicate<String, String> predicate,
      String name) {
    this.sourceString = sourceString;
    this.str = str;
    this.predicate = predicate;
    this.name = name;
  }

  @Override
  public Boolean evaluate(ValueReferenceResolver valueRefResolver) {
    Value<?> sourceValue =
        sourceString != null ? sourceString.evaluate(valueRefResolver) : Value.nullValue();
    if (sourceValue.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", PrettyPrintVisitor.print(this));
    }
    if (sourceValue.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", PrettyPrintVisitor.print(this));
    }
    if (sourceValue.getValue() instanceof String) {
      String sourceStr = (String) sourceValue.getValue();
      return predicate.test(sourceStr, str.getValue());
    }
    return Boolean.FALSE;
  }

  public ValueExpression<?> getSourceString() {
    return sourceString;
  }

  public StringValue getStr() {
    return str;
  }

  public BiPredicate<String, String> getPredicate() {
    return predicate;
  }

  public String getName() {
    return name;
  }
}
