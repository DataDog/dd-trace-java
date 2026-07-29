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
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Implements expression language for capturing values for metric probes */
public class ValueScript implements DebuggerScript<Value<?>> {
  private static final Pattern PERIOD_PATTERN = Pattern.compile("\\.");
  private static final Pattern INDEX_PATTERN = Pattern.compile("(.+)\\[([^]]+)]");
  private final ValueExpression<?> expr;
  private final String dsl;

  public ValueScript(ValueExpression<?> expr, String dsl) {
    this.expr = expr;
    this.dsl = dsl;
  }

  public String getDsl() {
    return dsl;
  }

  public ValueExpression<?> getExpr() {
    return expr;
  }

  @Override
  public Value<?> execute(ValueReferenceResolver valueRefResolver, TimeoutChecker timeoutChecker) {
    return expr.evaluate(new EvalContext(valueRefResolver, timeoutChecker));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ValueScript that = (ValueScript) o;
    return Objects.equals(expr, that.expr) && Objects.equals(dsl, that.dsl);
  }

  @Override
  public int hashCode() {
    return Objects.hash(expr, dsl);
  }

  @Override
  public String toString() {
    return "ValueScript{dsl='" + dsl + '\'' + '}';
  }

  public static ValueExpression<?> parseRefPath(String refPath) {
    String[] parts = PERIOD_PATTERN.split(refPath);
    String head;
    int startIdx = 1;
    head = parts[0];
    ValueExpression<?> current;
    Matcher matcher = INDEX_PATTERN.matcher(head);
    if (matcher.matches()) {
      String key = matcher.group(2);
      ValueExpression<?> keyExpr;
      if (key.matches("[0-9]+")) {
        keyExpr = DSL.value(Integer.parseInt(key));
      } else if (key.matches("[\"'].*[\"']")) {
        keyExpr = DSL.value(key.substring(1, key.length() - 1));
      } else {
        keyExpr = parseRefPath(key);
      }
      current = DSL.index(DSL.ref(matcher.group(1)), keyExpr);
    } else {
      current = DSL.ref(head);
    }
    for (int i = startIdx; i < parts.length; i++) {
      current = DSL.getMember(current, parts[i]);
    }
    return current;
  }

  public static class ValueScriptAdapter extends JsonAdapter<ValueScript> {
    @Override
    public ValueScript fromJson(JsonReader jsonReader) throws IOException {
      if (jsonReader.peek() == JsonReader.Token.BEGIN_OBJECT) {
        jsonReader.beginObject();
        ValueExpression<?> valueExpression = null;
        String dsl = null;
        while (jsonReader.hasNext()) {
          String fieldName = jsonReader.nextName();
          switch (fieldName) {
            case "json":
              {
                valueExpression = JsonToExpressionConverter.asValueExpression(jsonReader);
                break;
              }
            case "dsl":
              {
                dsl = jsonReader.nextString();
                break;
              }
            default:
              throw new IOException("Invalid field: " + fieldName);
          }
        }
        jsonReader.endObject();
        return new ValueScript(valueExpression, dsl);
      } else {
        throw new IOException("Invalid ValueScript format");
      }
    }

    @Override
    public void toJson(JsonWriter jsonWriter, ValueScript value) throws IOException {
      if (value == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginObject();
      jsonWriter.name("dsl");
      jsonWriter.value(value.dsl);
      jsonWriter.name("json");
      ToJsonVisitor toJsonVisitor = new ToJsonVisitor(jsonWriter);
      value.expr.accept(toJsonVisitor);
      jsonWriter.endObject();
    }

    private static class ToJsonVisitor implements Visitor<Void> {
      private final JsonWriter jsonWriter;

      public ToJsonVisitor(JsonWriter jsonWriter) {
        this.jsonWriter = jsonWriter;
      }

      @Override
      public Void visit(BinaryExpression binaryExpression) {
        throw new UnsupportedOperationException("BinaryExpression is not supported");
      }

      @Override
      public Void visit(BinaryOperator operator) {
        throw new UnsupportedOperationException("BinaryOperator is not supported");
      }

      @Override
      public Void visit(ComparisonExpression comparisonExpression) {
        try {
          jsonWriter.beginObject();
          comparisonExpression.getOperator().accept(this);
          jsonWriter.beginArray();
          comparisonExpression.getLeft().accept(this);
          comparisonExpression.getRight().accept(this);
          jsonWriter.endArray();
          jsonWriter.endObject();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(ComparisonOperator operator) {
        try {
          jsonWriter.name(operator.name().toLowerCase());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(ContainsExpression containsExpression) {
        throw new UnsupportedOperationException("ContainsExpression is not supported");
      }

      @Override
      public Void visit(EndsWithExpression endsWithExpression) {
        throw new UnsupportedOperationException("EndsWithExpression is not supported");
      }

      @Override
      public Void visit(FilterCollectionExpression filterCollectionExpression) {
        try {
          jsonWriter.beginObject();
          jsonWriter.name("filter");
          jsonWriter.beginArray();
          filterCollectionExpression.getSource().accept(this);
          filterCollectionExpression.getFilterExpression().accept(this);
          jsonWriter.endArray();
          jsonWriter.endObject();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(HasAllExpression hasAllExpression) {
        throw new UnsupportedOperationException("HasAllExpression is not supported");
      }

      @Override
      public Void visit(HasAnyExpression hasAnyExpression) {
        throw new UnsupportedOperationException("HasAnyExpression is not supported");
      }

      @Override
      public Void visit(IfElseExpression ifElseExpression) {
        throw new UnsupportedOperationException("IfElseExpression is not supported");
      }

      @Override
      public Void visit(IfExpression ifExpression) {
        throw new UnsupportedOperationException("IfExpression is not supported");
      }

      @Override
      public Void visit(IsEmptyExpression isEmptyExpression) {
        throw new UnsupportedOperationException("IsEmptyExpression is not supported");
      }

      @Override
      public Void visit(IsDefinedExpression isDefinedExpression) {
        throw new UnsupportedOperationException("IsDefinedExpression is not supported");
      }

      @Override
      public Void visit(LenExpression lenExpression) {
        try {
          jsonWriter.beginObject();
          jsonWriter.name("count");
          lenExpression.getSource().accept(this);
          jsonWriter.endObject();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(MatchesExpression matchesExpression) {
        throw new UnsupportedOperationException("MatchesExpression is not supported");
      }

      @Override
      public Void visit(NotExpression notExpression) {
        throw new UnsupportedOperationException("NotExpression is not supported");
      }

      @Override
      public Void visit(StartsWithExpression startsWithExpression) {
        throw new UnsupportedOperationException("StartsWithExpression is not supported");
      }

      @Override
      public Void visit(SubStringExpression subStringExpression) {
        throw new UnsupportedOperationException("SubStringExpression is not supported");
      }

      @Override
      public Void visit(ValueRefExpression valueRefExpression) {
        try {
          jsonWriter.beginObject();
          jsonWriter.name("ref");
          jsonWriter.value(valueRefExpression.getSymbolName());
          jsonWriter.endObject();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(GetMemberExpression getMemberExpression) {
        try {
          jsonWriter.beginObject();
          jsonWriter.name("getmember");
          jsonWriter.beginArray();
          getMemberExpression.getTarget().accept(this);
          jsonWriter.value(getMemberExpression.getMemberName());
          jsonWriter.endArray();
          jsonWriter.endObject();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(IndexExpression indexExpression) {
        try {
          jsonWriter.beginObject();
          jsonWriter.name("index");
          jsonWriter.beginArray();
          indexExpression.getTarget().accept(this);
          indexExpression.getKey().accept(this);
          jsonWriter.endArray();
          jsonWriter.endObject();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(WhenExpression whenExpression) {
        throw new UnsupportedOperationException("WhenExpression is not supported");
      }

      @Override
      public Void visit(BooleanExpression booleanExpression) {
        throw new UnsupportedOperationException("BooleanExpression is not supported");
      }

      @Override
      public Void visit(ObjectValue objectValue) {
        throw new UnsupportedOperationException("ObjectValue is not supported");
      }

      @Override
      public Void visit(StringValue stringValue) {
        try {
          jsonWriter.value(stringValue.getValue());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(NumericValue numericValue) {
        try {
          // TODO handle double
          jsonWriter.value(numericValue.getValue().longValue());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      }

      @Override
      public Void visit(BooleanValue booleanValue) {
        throw new UnsupportedOperationException("BooleanValue is not supported");
      }

      @Override
      public Void visit(NullValue nullValue) {
        throw new UnsupportedOperationException("NullValue is not supported");
      }

      @Override
      public Void visit(ListValue listValue) {
        throw new UnsupportedOperationException("ListValue is not supported");
      }

      @Override
      public Void visit(MapValue mapValue) {
        throw new UnsupportedOperationException("MapValue is not supported");
      }

      @Override
      public Void visit(SetValue setValue) {
        throw new UnsupportedOperationException("SetValue is not supported");
      }
    }
  }
}
