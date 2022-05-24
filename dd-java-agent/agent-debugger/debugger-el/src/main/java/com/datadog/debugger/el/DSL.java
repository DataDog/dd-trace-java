package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.BinaryExpression;
import com.datadog.debugger.el.expressions.ComparisonExpression;
import com.datadog.debugger.el.expressions.FilterCollectionExpression;
import com.datadog.debugger.el.expressions.HasAllExpression;
import com.datadog.debugger.el.expressions.HasAnyExpression;
import com.datadog.debugger.el.expressions.IfElseExpression;
import com.datadog.debugger.el.expressions.IfExpression;
import com.datadog.debugger.el.expressions.IsEmptyExpression;
import com.datadog.debugger.el.expressions.LenExpression;
import com.datadog.debugger.el.expressions.NotExpression;
import com.datadog.debugger.el.expressions.PredicateExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.expressions.ValueRefExpression;
import com.datadog.debugger.el.expressions.WhenExpression;
import com.datadog.debugger.el.predicates.AndPredicate;
import com.datadog.debugger.el.predicates.EqualsPredicate;
import com.datadog.debugger.el.predicates.GreaterOrEqualPredicate;
import com.datadog.debugger.el.predicates.GreaterThanPredicate;
import com.datadog.debugger.el.predicates.LessOrEqualPredicate;
import com.datadog.debugger.el.predicates.LessThanPredicate;
import com.datadog.debugger.el.predicates.OrPredicate;
import com.datadog.debugger.el.values.BooleanValue;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.NumericValue;
import com.datadog.debugger.el.values.ObjectValue;
import com.datadog.debugger.el.values.StringValue;
import java.util.Collection;
import java.util.Map;

/**
 * A debugger DSL representation. A simple all-static class which can be used to build a complete
 * debugger EL expression tree.
 */
public class DSL {
  private DSL() {}

  public static final PredicateExpression TRUE = PredicateExpression.TRUE;
  public static final PredicateExpression FALSE = PredicateExpression.FALSE;

  public static PredicateExpression and(PredicateExpression... expressions) {
    if (expressions.length == 0) {
      return FALSE;
    }
    PredicateExpression expression = expressions[0];
    for (int i = 1; i < expressions.length; i++) {
      expression = and(expression, expressions[i]);
    }
    return expression;
  }

  public static PredicateExpression and(PredicateExpression left, PredicateExpression right) {
    return new BinaryExpression(left, right, AndPredicate::new);
  }

  public static PredicateExpression or(PredicateExpression... expressions) {
    if (expressions.length == 0) {
      return FALSE;
    }
    PredicateExpression expression = expressions[0];
    for (int i = 1; i < expressions.length; i++) {
      expression = or(expression, expressions[i]);
    }
    return expression;
  }

  public static PredicateExpression or(PredicateExpression left, PredicateExpression right) {
    return new BinaryExpression(left, right, OrPredicate::new);
  }

  public static PredicateExpression gt(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, GreaterThanPredicate::new);
  }

  public static PredicateExpression ge(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, GreaterOrEqualPredicate::new);
  }

  public static PredicateExpression lt(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, LessThanPredicate::new);
  }

  public static PredicateExpression le(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, LessOrEqualPredicate::new);
  }

  public static PredicateExpression eq(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, EqualsPredicate::new);
  }

  public static PredicateExpression not(PredicateExpression expression) {
    return new NotExpression(expression);
  }

  public static IfExpression doif(PredicateExpression test, Expression<?> expression) {
    return new IfExpression(test, expression);
  }

  public static IfElseExpression doif(
      PredicateExpression test, Expression<?> thenExpression, Expression<?> elseExpression) {
    return new IfElseExpression(test, thenExpression, elseExpression);
  }

  public static ValueRefExpression ref(String path) {
    return new ValueRefExpression(path);
  }

  public static Literal<Boolean> value(boolean value) {
    return new BooleanValue(value);
  }

  public static Literal<Number> value(Number value) {
    return new NumericValue(value);
  }

  public static Literal<String> value(String value) {
    return new StringValue(value);
  }

  public static ListValue value(Collection<?> value) {
    return new ListValue(value);
  }

  public static MapValue value(Map<?, ?> value) {
    return new MapValue(value);
  }

  public static ValueExpression<?> value(Object value) {
    if (value != null) {
      if (value.getClass().isArray()) {
        return new ListValue(value);
      }
    }
    return new ObjectValue(value);
  }

  public static IsEmptyExpression isEmpty(ValueExpression<?> valueExpression) {
    return new IsEmptyExpression(valueExpression);
  }

  public static HasAnyExpression any(
      ValueExpression<?> valueExpression, PredicateExpression filter) {
    return new HasAnyExpression(valueExpression, filter);
  }

  public static HasAllExpression all(
      ValueExpression<?> valueExpression, PredicateExpression filter) {
    return new HasAllExpression(valueExpression, filter);
  }

  public static FilterCollectionExpression filter(
      ValueExpression<?> valueExpression, PredicateExpression filter) {
    return new FilterCollectionExpression(valueExpression, filter);
  }

  public static ValueExpression<Value<? extends Number>> len(ValueExpression<?> valueExpression) {
    return new LenExpression(valueExpression);
  }

  public static WhenExpression when(PredicateExpression expression) {
    return new WhenExpression(expression);
  }
}
