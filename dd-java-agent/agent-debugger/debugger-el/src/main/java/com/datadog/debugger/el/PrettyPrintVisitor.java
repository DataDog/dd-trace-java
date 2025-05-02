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
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PrettyPrintVisitor implements Visitor<String> {

  public static String print(Expression<?> expr) {
    return expr.accept(new PrettyPrintVisitor());
  }

  @Override
  public String visit(BinaryExpression binaryExpression) {
    return nullSafeAccept(binaryExpression.getLeft())
        + " "
        + binaryExpression.getOperator().accept(this)
        + " "
        + nullSafeAccept(binaryExpression.getRight());
  }

  @Override
  public String visit(BinaryOperator operator) {
    return operator.getSymbol();
  }

  @Override
  public String visit(ComparisonExpression comparisonExpression) {
    return nullSafeAccept(comparisonExpression.getLeft())
        + " "
        + comparisonExpression.getOperator().accept(this)
        + " "
        + nullSafeAccept(comparisonExpression.getRight());
  }

  @Override
  public String visit(ComparisonOperator operator) {
    return operator.getSymbol();
  }

  @Override
  public String visit(ContainsExpression containsExpression) {
    return "contains("
        + nullSafeAccept(containsExpression.getTarget())
        + ", "
        + nullSafeAccept(containsExpression.getValue())
        + ")";
  }

  @Override
  public String visit(EndsWithExpression endsWithExpression) {
    return stringPredicateExpression(endsWithExpression);
  }

  @Override
  public String visit(FilterCollectionExpression filterCollectionExpression) {
    return "filter("
        + nullSafeAccept(filterCollectionExpression.getSource())
        + ", {"
        + nullSafeAccept(filterCollectionExpression.getFilterExpression())
        + "})";
  }

  @Override
  public String visit(HasAllExpression hasAllExpression) {
    return "all("
        + nullSafeAccept(hasAllExpression.getValueExpression())
        + ", {"
        + nullSafeAccept(hasAllExpression.getFilterPredicateExpression())
        + "})";
  }

  @Override
  public String visit(HasAnyExpression hasAnyExpression) {
    return "any("
        + nullSafeAccept(hasAnyExpression.getValueExpression())
        + ", {"
        + nullSafeAccept(hasAnyExpression.getFilterPredicateExpression())
        + "})";
  }

  @Override
  public String visit(IfElseExpression ifElseExpression) {
    return "if "
        + nullSafeAccept(ifElseExpression.getTest())
        + " then "
        + nullSafeAccept(ifElseExpression.getThenExpression())
        + " else "
        + nullSafeAccept(ifElseExpression.getElseExpression());
  }

  @Override
  public String visit(IfExpression ifExpression) {
    return "if "
        + nullSafeAccept(ifExpression.getTest())
        + " then "
        + nullSafeAccept(ifExpression.getExpression());
  }

  @Override
  public String visit(IsEmptyExpression isEmptyExpression) {
    return "isEmpty(" + nullSafeAccept(isEmptyExpression.getValueExpression()) + ")";
  }

  @Override
  public String visit(IsDefinedExpression isDefinedExpression) {
    return "isDefined(" + nullSafeAccept(isDefinedExpression.getValueExpression()) + ")";
  }

  @Override
  public String visit(LenExpression lenExpression) {
    return "len(" + nullSafeAccept(lenExpression.getSource()) + ")";
  }

  @Override
  public String visit(MatchesExpression matchesExpression) {
    return stringPredicateExpression(matchesExpression);
  }

  @Override
  public String visit(NotExpression notExpression) {
    return "not(" + nullSafeAccept(notExpression.getPredicate()) + ")";
  }

  @Override
  public String visit(StartsWithExpression startsWithExpression) {
    return stringPredicateExpression(startsWithExpression);
  }

  @Override
  public String visit(SubStringExpression subStringExpression) {
    return "substring("
        + nullSafeAccept(subStringExpression.getSource())
        + ", "
        + subStringExpression.getStartIndex()
        + ", "
        + subStringExpression.getEndIndex()
        + ")";
  }

  @Override
  public String visit(ValueRefExpression valueRefExpression) {
    return valueRefExpression.getSymbolName();
  }

  @Override
  public String visit(GetMemberExpression getMemberExpression) {
    return nullSafeAccept(getMemberExpression.getTarget())
        + "."
        + getMemberExpression.getMemberName();
  }

  @Override
  public String visit(IndexExpression indexExpression) {
    return nullSafeAccept(indexExpression.getTarget())
        + "["
        + nullSafeAccept(indexExpression.getKey())
        + "]";
  }

  @Override
  public String visit(WhenExpression whenExpression) {
    return "when(" + nullSafeAccept(whenExpression.getExpression()) + ")";
  }

  @Override
  public String visit(BooleanExpression booleanExpression) {
    return booleanExpression.toString();
  }

  @Override
  public String visit(StringValue stringValue) {
    return "\"" + stringValue.value + "\"";
  }

  @Override
  public String visit(ObjectValue objectValue) {
    Object value = objectValue.value;
    if (value == null || value == Values.NULL_OBJECT) {
      return "null";
    }
    if (value == Values.UNDEFINED_OBJECT) {
      return value.toString();
    }
    if (value == Values.THIS_OBJECT) {
      return "this";
    }
    return value.getClass().getTypeName();
  }

  @Override
  public String visit(NumericValue numericValue) {
    Number value = numericValue.value;
    if (value != null) {
      if (value instanceof Double || value instanceof Float) {
        return String.valueOf(value.doubleValue());
      }
      return value.toString();
    }
    return "null";
  }

  @Override
  public String visit(BooleanValue booleanValue) {
    if (booleanValue.value == null) {
      return "null";
    }
    return String.valueOf(booleanValue.value.booleanValue());
  }

  @Override
  public String visit(NullValue nullValue) {
    return "null";
  }

  @Override
  public String visit(ListValue listValue) {
    if (listValue.getArrayHolder() != null) {
      return listValue.getArrayType().getTypeName() + "[]";
    }
    if (listValue.getListHolder() instanceof List) {
      return "List";
    }
    if (listValue.getListHolder() instanceof Set) {
      return "Set";
    }
    return "null";
  }

  @Override
  public String visit(MapValue mapValue) {
    if (mapValue.getMapHolder() instanceof Map) {
      return "Map";
    }
    return "null";
  }

  @Override
  public String visit(SetValue setValue) {
    if (setValue.getSetHolder() instanceof Set) {
      return "Set";
    }
    return "null";
  }

  private String stringPredicateExpression(StringPredicateExpression stringPredicateExpression) {
    return stringPredicateExpression.getName()
        + "("
        + nullSafeAccept(stringPredicateExpression.getSourceString())
        + ", "
        + nullSafeAccept(stringPredicateExpression.getStr())
        + ")";
  }

  private String nullSafeAccept(Expression<?> expr) {
    return expr != null ? expr.accept(this) : "null";
  }
}
