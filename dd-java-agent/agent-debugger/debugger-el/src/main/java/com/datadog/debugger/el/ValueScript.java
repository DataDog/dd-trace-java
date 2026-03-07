package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.GetMemberExpression;
import com.datadog.debugger.el.expressions.IndexExpression;
import com.datadog.debugger.el.expressions.LenExpression;
import com.datadog.debugger.el.expressions.ValueExpression;
import com.datadog.debugger.el.expressions.ValueRefExpression;
import com.datadog.debugger.el.values.StringValue;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import datadog.trace.util.HashingUtils;

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
  public Value<?> execute(ValueReferenceResolver valueRefResolver) {
    return expr.evaluate(valueRefResolver);
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
    return HashingUtils.hash(expr, dsl);
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
      writeValueExpression(jsonWriter, value.expr);
      jsonWriter.endObject();
    }

    private void writeValueExpression(JsonWriter jsonWriter, ValueExpression<?> expr)
        throws IOException {
      if (expr instanceof Value) {
        if (expr instanceof StringValue) {
          jsonWriter.value(((StringValue) expr).getValue());
        } else {
          throw new IOException("Unsupported operation: " + expr.getClass().getTypeName());
        }
        return;
      }
      jsonWriter.beginObject();
      if (expr instanceof ValueRefExpression) {
        ValueRefExpression valueRefExpr = (ValueRefExpression) expr;
        jsonWriter.name("ref");
        jsonWriter.value(valueRefExpr.getSymbolName());
      } else if (expr instanceof GetMemberExpression) {
        GetMemberExpression getMemberExpr = (GetMemberExpression) expr;
        jsonWriter.name("getmember");
        jsonWriter.beginArray();
        writeValueExpression(jsonWriter, getMemberExpr.getTarget());
        jsonWriter.value(getMemberExpr.getMemberName());
        jsonWriter.endArray();
      } else if (expr instanceof LenExpression) {
        jsonWriter.name("count");
        writeValueExpression(jsonWriter, ((LenExpression) expr).getSource());
      } else if (expr instanceof IndexExpression) {
        IndexExpression idxExpr = (IndexExpression) expr;
        jsonWriter.name("index");
        jsonWriter.beginArray();
        writeValueExpression(jsonWriter, idxExpr.getTarget());
        writeValueExpression(jsonWriter, idxExpr.getKey());
        jsonWriter.endArray();
      } else {
        throw new IOException("Unsupported operation: " + expr.getClass().getTypeName());
      }
      jsonWriter.endObject();
    }
  }
}
