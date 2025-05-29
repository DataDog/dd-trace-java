package com.datadog.debugger.el.expressions;

import static com.datadog.debugger.el.PrettyPrintVisitor.print;

import com.datadog.debugger.el.EvaluationException;
import com.datadog.debugger.el.Expression;
import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.values.ListValue;
import com.datadog.debugger.el.values.MapValue;
import com.datadog.debugger.el.values.SetValue;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CollectionExpressionHelper {
  public static void checkSupportedMap(MapValue map, Expression<?> expression) {
    Map<?, ?> mapHolder = (Map<?, ?>) map.getMapHolder();
    if (!WellKnownClasses.isSafe(mapHolder)) {
      throw new EvaluationException(
          "Unsupported Map class: " + mapHolder.getClass().getTypeName(), print(expression));
    }
  }

  public static void checkSupportedList(ListValue collection, Expression<?> expression) {
    Object holder = collection.getValue();
    if (holder instanceof List) {
      if (!WellKnownClasses.isSafe((List<?>) holder)) {
        throw new EvaluationException(
            "Unsupported List class: " + holder.getClass().getTypeName(), print(expression));
      }
    }
  }

  public static Value<?> evaluateTargetCollection(
      ValueExpression<?> collectionTarget,
      Expression<?> expression,
      ValueReferenceResolver valueRefResolver) {
    if (collectionTarget == null) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", print(expression));
    }
    Value<?> value = collectionTarget.evaluate(valueRefResolver);
    if (value.isUndefined()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for undefined value", print(expression));
    }
    if (value.isNull()) {
      throw new EvaluationException(
          "Cannot evaluate the expression for null value", print(expression));
    }
    return value;
  }

  public static Set<?> checkSupportedSet(SetValue set, Expression<?> expression) {
    Set<?> setHolder = (Set<?>) set.getSetHolder();
    if (!WellKnownClasses.isSafe(setHolder)) {
      throw new EvaluationException(
          "Unsupported Set class: " + setHolder.getClass().getTypeName(), print(expression));
    }
    return setHolder;
  }
}
