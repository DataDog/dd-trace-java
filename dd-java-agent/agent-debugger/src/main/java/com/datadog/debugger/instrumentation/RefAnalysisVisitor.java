package com.datadog.debugger.instrumentation;

import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.BinaryExpression;
import com.datadog.debugger.el.expressions.BinaryOperator;
import com.datadog.debugger.el.expressions.BooleanExpression;
import com.datadog.debugger.el.expressions.ComparisonExpression;
import com.datadog.debugger.el.expressions.ComparisonOperator;
import com.datadog.debugger.el.expressions.ContainsExpression;
import com.datadog.debugger.el.expressions.EndsWithExpression;
import com.datadog.debugger.el.expressions.FilterCollectionExpression;
import com.datadog.debugger.el.expressions.GetMemberExpression;
import com.datadog.debugger.el.expressions.HasAllExpression;
import com.datadog.debugger.el.expressions.HasAnyExpression;
import com.datadog.debugger.el.expressions.IfElseExpression;
import com.datadog.debugger.el.expressions.IfExpression;
import com.datadog.debugger.el.expressions.IndexExpression;
import com.datadog.debugger.el.expressions.IsDefinedExpression;
import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.expressions.LenExpression;
import com.datadog.debugger.el.expressions.MatchesExpression;
import com.datadog.debugger.el.expressions.NotExpression;
import com.datadog.debugger.el.expressions.StartsWithExpression;
import com.datadog.debugger.el.expressions.SubStringExpression;
import com.datadog.debugger.el.expressions.ValueRefExpression;
import com.datadog.debugger.el.expressions.WhenExpression;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NullValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.SetValue;
import com.datadog.debugger.el.values.StringValue;

public class RefAnalysisVisitor implements Visitor<Void> {
  @Override
  public Void visit(BinaryExpression binaryExpression) {
    binaryExpression.getLeft().accept(this);
    binaryExpression.getRight().accept(this);
    return null;
  }

  @Override
  public Void visit(BinaryOperator operator) {
    operator.accept(this);
    return null;
  }

  @Override
  public Void visit(ComparisonExpression comparisonExpression) {
    comparisonExpression.getLeft().accept(this);
    comparisonExpression.getRight().accept(this);
    comparisonExpression.getOperator().accept(this);
    return null;
  }

  @Override
  public Void visit(ComparisonOperator operator) {
    return null;
  }

  @Override
  public Void visit(ContainsExpression containsExpression) {
    containsExpression.getTarget().accept(this);
    containsExpression.getValue().accept(this);
    return null;
  }

  @Override
  public Void visit(EndsWithExpression endsWithExpression) {
    endsWithExpression.getSourceString().accept(this);
    return null;
  }

  @Override
  public Void visit(FilterCollectionExpression filterCollectionExpression) {
    filterCollectionExpression.getSource().accept(this);
    filterCollectionExpression.getFilterExpression().accept(this);
    return null;
  }

  @Override
  public Void visit(HasAllExpression hasAllExpression) {
    hasAllExpression.getFilterPredicateExpression().accept(this);
    hasAllExpression.getValueExpression().accept(this);
    return null;
  }

  @Override
  public Void visit(HasAnyExpression hasAnyExpression) {
    hasAnyExpression.getFilterPredicateExpression().accept(this);
    hasAnyExpression.getValueExpression().accept(this);
    return null;
  }

  @Override
  public Void visit(IfElseExpression ifElseExpression) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public Void visit(IfExpression ifExpression) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public Void visit(IsEmptyExpression isEmptyExpression) {
    isEmptyExpression.getValueExpression().accept(this);
    return null;
  }

  @Override
  public Void visit(IsDefinedExpression isDefinedExpression) {
    isDefinedExpression.getValueExpression().accept(this);
    return null;
  }

  @Override
  public Void visit(LenExpression lenExpression) {
    lenExpression.getSource().accept(this);
    return null;
  }

  @Override
  public Void visit(MatchesExpression matchesExpression) {
    matchesExpression.getSourceString().accept(this);
    return null;
  }

  @Override
  public Void visit(NotExpression notExpression) {
    notExpression.getPredicate().accept(this);
    return null;
  }

  @Override
  public Void visit(StartsWithExpression startsWithExpression) {
    startsWithExpression.getSourceString().accept(this);
    return null;
  }

  @Override
  public Void visit(SubStringExpression subStringExpression) {
    subStringExpression.getSource().accept(this);
    return null;
  }

  @Override
  public Void visit(ValueRefExpression valueRefExpression) {
    return null;
  }

  @Override
  public Void visit(GetMemberExpression getMemberExpression) {
    getMemberExpression.getTarget().accept(this);
    return null;
  }

  @Override
  public Void visit(IndexExpression indexExpression) {
    indexExpression.getTarget().accept(this);
    indexExpression.getKey().accept(this);
    return null;
  }

  @Override
  public Void visit(WhenExpression whenExpression) {
    whenExpression.getExpression().accept(this);
    return null;
  }

  @Override
  public Void visit(BooleanExpression booleanExpression) {
    return null;
  }

  @Override
  public Void visit(ObjectValue objectValue) {
    return null;
  }

  @Override
  public Void visit(StringValue stringValue) {
    return null;
  }

  @Override
  public Void visit(NumericValue numericValue) {
    return null;
  }

  @Override
  public Void visit(BooleanValue booleanValue) {
    return null;
  }

  @Override
  public Void visit(NullValue nullValue) {
    return null;
  }

  @Override
  public Void visit(ListValue listValue) {
    return null;
  }

  @Override
  public Void visit(MapValue mapValue) {
    return null;
  }

  @Override
  public Void visit(SetValue setValue) {
    return null;
  }
}
