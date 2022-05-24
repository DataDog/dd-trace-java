package com.datadog.debugger.el;

import com.datadog.debugger.el.expressions.ValueExpression;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import java.io.IOException;
import java.util.Objects;

/** Implements expression language for capturing values for metric probes */
public class ValueScript implements DebuggerScript {
  private final Object node;
  private Value<?> result;

  public ValueScript(Object node) {
    this.node = node;
  }

  @Override
  public boolean execute(ValueReferenceResolver valueRefResolver) {
    if (node == null) {
      return true;
    }
    ValueExpression<? extends Value<?>> valueExpr = mapToValueExpression(node);
    result = valueExpr.evaluate(valueRefResolver);
    return true;
  }

  public Value<?> getResult() {
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ValueScript that = (ValueScript) o;
    return Objects.equals(node, that.node);
  }

  @Override
  public int hashCode() {
    return Objects.hash(node);
  }

  @Override
  public String toString() {
    return "ValueScript{" + "node=" + node + '}';
  }

  private ValueExpression<? extends Value<?>> mapToValueExpression(Object node) {
    if (node instanceof Integer) {
      return DSL.value(((Integer) node).longValue());
    }
    if (node instanceof Long) {
      return DSL.value((Long) node);
    }
    if (node instanceof String) {
      String textValue = (String) node;
      if (ValueReferences.isRefExpression(textValue)) {
        return DSL.ref(textValue);
      }
      throw new InvalidValueException("Invalid value definition: " + node);
    }
    throw new InvalidValueException(
        "Invalid type value definition: " + node + ", expect text or integral type.");
  }

  public static class ValueScriptAdapter extends JsonAdapter<ValueScript> {
    @Override
    public ValueScript fromJson(JsonReader jsonReader) throws IOException {
      jsonReader.beginObject();
      String fieldName = jsonReader.nextName();
      if (fieldName.equals("expr")) {
        Object obj = jsonReader.readJsonValue();
        jsonReader.endObject();
        return new ValueScript(obj);
      } else {
        throw new IOException("Invalid field: " + fieldName);
      }
    }

    @Override
    public void toJson(JsonWriter jsonWriter, ValueScript value) throws IOException {
      if (value == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginObject();
      jsonWriter.name("expr");
      if (value.node instanceof String) {
        jsonWriter.value((String) value.node);
      }
      jsonWriter.endObject();
    }
  }
}
