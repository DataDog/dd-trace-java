package com.datadog.debugger.el;

import static com.datadog.debugger.el.JsonToExpressionConverter.createPredicate;

import com.datadog.debugger.el.expressions.ThenExpression;
import com.datadog.debugger.el.expressions.WhenExpression;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/** Implements expression language for probe condition */
public final class ProbeCondition implements DebuggerScript<Boolean> {
  public static final ProbeCondition NONE = new ProbeCondition(null, "");

  private final String dslExpression;
  private final WhenExpression when;
  private final ThenExpression then;

  public ProbeCondition(WhenExpression when, String dslExpression) {
    this.when = when;
    this.dslExpression = dslExpression;
    this.then = new ThenExpression();
  }

  public String getDslExpression() {
    return dslExpression;
  }

  public WhenExpression getWhen() {
    return when;
  }

  public static class ProbeConditionJsonAdapter extends JsonAdapter<ProbeCondition> {
    @Override
    public ProbeCondition fromJson(JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        reader.nextNull();
        return null;
      }
      reader.beginObject();
      ProbeCondition probeCondition = load(reader);
      reader.endObject();
      return probeCondition;
    }

    @Override
    public void toJson(@NonNull JsonWriter jsonWriter, ProbeCondition value) throws IOException {
      if (value == null) {
        jsonWriter.nullValue();
        return;
      }
      jsonWriter.beginObject();
      jsonWriter.name("dsl");
      jsonWriter.value(value.dslExpression);
      jsonWriter.endObject();
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    ProbeCondition that = (ProbeCondition) o;

    return getDslExpression().equals(that.getDslExpression());
  }

  @Override
  public int hashCode() {
    return getDslExpression().hashCode();
  }

  @Override
  public String toString() {
    return "ProbeCondition{" + "dslExpression='" + dslExpression + '\'' + '}';
  }

  public static ProbeCondition load(JsonReader reader) throws IOException {
    // reader is expected to be inside "when"
    String dsl = null;
    WhenExpression expression = null;
    while (reader.hasNext() && reader.peek() == JsonReader.Token.NAME) {
      String fieldName = reader.nextName();
      if ("dsl".equals(fieldName)) {
        dsl = reader.nextString();
      } else if ("json".equals(fieldName)) {
        expression = DSL.when(createPredicate(reader));
      } else {
        reader.skipValue();
      }
    }
    // check dsl and condition for null
    return new ProbeCondition(expression, dsl);
  }

  @Override
  public Boolean execute(ValueReferenceResolver valueRefResolver) {
    if (when == null) {
      return true;
    }
    if (when.evaluate(valueRefResolver)) {
      then.evaluate(valueRefResolver);
      return true;
    }
    return false;
  }

  public void accept(Visitor visitor) {
    when.accept(visitor);
  }
}
