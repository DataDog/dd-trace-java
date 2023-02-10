package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.*;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.StringValue;

public interface Visitor<R> {
  R visit(BinaryExpression binaryExpression);

  R visit(BinaryOperator operator);

  R visit(ComparisonExpression comparisonExpression);

  R visit(ComparisonOperator operator);

  R visit(ContainsExpression containsExpression);

  R visit(EndsWithExpression endsWithExpression);

  R visit(FilterCollectionExpression filterCollectionExpression);

  R visit(HasAllExpression hasAllExpression);

  R visit(HasAnyExpression hasAnyExpression);

  R visit(IfElseExpression ifElseExpression);

  R visit(IfExpression ifExpression);

  R visit(IsEmptyExpression isEmptyExpression);

  R visit(IsUndefinedExpression isUndefinedExpression);

  R visit(LenExpression lenExpression);

  R visit(MatchesExpression matchesExpression);

  R visit(NotExpression notExpression);

  R visit(StartsWithExpression startsWithExpression);

  R visit(SubStringExpression subStringExpression);

  R visit(ValueRefExpression valueRefExpression);

  R visit(GetMemberExpression getMemberExpression);

  R visit(IndexExpression indexExpression);

  R visit(WhenExpression whenExpression);

  R visit(BooleanExpression booleanExpression);

  R visit(ObjectValue objectValue);

  R visit(StringValue stringValue);

  R visit(NumericValue numericValue);

  R visit(BooleanValue booleanValue);

  R visit(ListValue listValue);

  R visit(MapValue mapValue);
}
