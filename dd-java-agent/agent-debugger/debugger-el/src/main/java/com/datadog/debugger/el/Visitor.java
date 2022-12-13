package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.*;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.StringValue;

public interface Visitor<R> {
  R visit(BinaryExpression binaryExpression);

  R visit(ComparisonExpression comparisonExpression);

  R visit(FilterCollectionExpression filterCollectionExpression);

  R visit(HasAllExpression hasAllExpression);

  R visit(HasAnyExpression hasAnyExpression);

  R visit(IfElseExpression ifElseExpression);

  R visit(IfExpression ifExpression);

  R visit(IsEmptyExpression isEmptyExpression);

  R visit(IsUndefinedExpression isUndefinedExpression);

  R visit(LenExpression lenExpression);

  R visit(NotExpression notExpression);

  R visit(ValueRefExpression valueRefExpression);

  R visit(GetMemberExpression getMemberExpression);

  R visit(WhenExpression whenExpression);

  R visit(StringValue stringValue);

  R visit(NumericValue numericValue);

  R visit(PredicateExpression predicate);
}
