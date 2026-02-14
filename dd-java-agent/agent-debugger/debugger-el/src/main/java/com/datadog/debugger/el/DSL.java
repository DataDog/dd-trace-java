package com.datadog.debugger.el;

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
import com.datadog.debugger.el.expressions.StringPredicateExpression;
import com.datadog.debugger.el.expressions.SubStringExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
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
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * A debugger DSL representation. A simple all-static class which can be used to build a complete
 * debugger EL expression tree.
 */
public class DSL {
  private DSL() {}

  public static final BooleanExpression TRUE = BooleanExpression.TRUE;
  public static final BooleanExpression FALSE = BooleanExpression.FALSE;

  public static BooleanExpression and(BooleanExpression... expressions) {
    if (expressions.length == 0) {
      return FALSE;
    }
    BooleanExpression expression = expressions[0];
    for (int i = 1; i < expressions.length; i++) {
      expression = and(expression, expressions[i]);
    }
    return expression;
  }

  public static BooleanExpression and(BooleanExpression left, BooleanExpression right) {
    return new BinaryExpression(left, right, BinaryOperator.AND);
  }

  public static BooleanExpression or(BooleanExpression... expressions) {
    if (expressions.length == 0) {
      return FALSE;
    }
    BooleanExpression expression = expressions[0];
    for (int i = 1; i < expressions.length; i++) {
      expression = or(expression, expressions[i]);
    }
    return expression;
  }

  public static BooleanExpression or(BooleanExpression left, BooleanExpression right) {
    return new BinaryExpression(left, right, BinaryOperator.OR);
  }

  public static BooleanExpression gt(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, ComparisonOperator.GT);
  }

  public static BooleanExpression ge(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, ComparisonOperator.GE);
  }

  public static BooleanExpression lt(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, ComparisonOperator.LT);
  }

  public static BooleanExpression le(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, ComparisonOperator.LE);
  }

  public static BooleanExpression eq(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, ComparisonOperator.EQ);
  }

  public static BooleanExpression instanceOf(ValueExpression<?> left, ValueExpression<?> right) {
    return new ComparisonExpression(left, right, ComparisonOperator.INSTANCEOF);
  }

  public static BooleanExpression not(BooleanExpression expression) {
    return new NotExpression(expression);
  }

  public static IfExpression doif(BooleanExpression test, Expression<?> expression) {
    return new IfExpression(test, expression);
  }

  public static IfElseExpression doif(
      BooleanExpression test, Expression<?> thenExpression, Expression<?> elseExpression) {
    return new IfElseExpression(test, thenExpression, elseExpression);
  }

  public static ValueRefExpression ref(String path) {
    return new ValueRefExpression(path);
  }

  public static GetMemberExpression getMember(ValueExpression<?> target, String name) {
    return new GetMemberExpression(target, name);
  }

  public static IndexExpression index(ValueExpression<?> target, ValueExpression<?> key) {
    return new IndexExpression(target, key);
  }

  public static Literal<Boolean> value(boolean value) {
    return new BooleanValue(value, ValueType.BOOLEAN);
  }

  public static Literal<Number> value(Number value) {
    return new NumericValue(value, ValueType.OBJECT);
  }

  public static Literal<String> value(String value) {
    return new StringValue(value);
  }

  public static ListValue value(Collection<?> value) {
    return new ListValue(value);
  }

  public static SetValue value(Set<?> value) {
    return new SetValue(value);
  }

  public static NullValue nullValue() {
    return NullValue.INSTANCE;
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

  public static HasAnyExpression any(ValueExpression<?> valueExpression, BooleanExpression filter) {
    return new HasAnyExpression(valueExpression, filter);
  }

  public static HasAllExpression all(ValueExpression<?> valueExpression, BooleanExpression filter) {
    return new HasAllExpression(valueExpression, filter);
  }

  public static FilterCollectionExpression filter(
      ValueExpression<?> valueExpression, BooleanExpression filter) {
    return new FilterCollectionExpression(valueExpression, filter);
  }

  public static LenExpression len(ValueExpression<?> valueExpression) {
    return new LenExpression(valueExpression);
  }

  public static SubStringExpression subString(
      ValueExpression<?> valueExpression, int startIndex, int endIndex) {
    return new SubStringExpression(valueExpression, startIndex, endIndex);
  }

  public static StringPredicateExpression startsWith(
      ValueExpression<?> valueExpression, StringValue str) {
    return new StartsWithExpression(valueExpression, str);
  }

  public static StringPredicateExpression endsWith(
      ValueExpression<?> valueExpression, StringValue str) {
    return new EndsWithExpression(valueExpression, str);
  }

  public static ContainsExpression contains(ValueExpression<?> target, ValueExpression<?> value) {
    return new ContainsExpression(target, value);
  }

  public static StringPredicateExpression matches(
      ValueExpression<?> valueExpression, StringValue str) {
    return new MatchesExpression(valueExpression, str);
  }

  public static WhenExpression when(BooleanExpression expression) {
    return new WhenExpression(expression);
  }

  public static BooleanValueExpressionAdapter bool(BooleanExpression expression) {
    return new BooleanValueExpressionAdapter(expression);
  }

  public static IsDefinedExpression isDefined(ValueExpression<?> valueExpression) {
    return new IsDefinedExpression(valueExpression);
  }
}
