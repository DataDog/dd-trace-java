package com.datadog.appsec.report;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

public class AppSecEventWrapper {

  private static final JsonAdapter<AppSecEventWrapper> ADAPTER =
      new Moshi.Builder()
          .add(Double.class, new IntegralDoubleJsonAdapter())
          .build()
          .adapter(AppSecEventWrapper.class);

  // Writes whole-number Doubles (e.g. key_path array indices) without a trailing ".0".

  private static final class IntegralDoubleJsonAdapter extends JsonAdapter<Double> {
    @Override
    public Double fromJson(JsonReader reader) throws IOException {
      return reader.nextDouble();
    }

    @Override
    public void toJson(JsonWriter writer, Double value) throws IOException {
      if (value == null) {
        writer.nullValue();
      } else if (!value.isInfinite() && !value.isNaN() && value == Math.rint(value)) {
        writer.value(value.longValue());
      } else {
        writer.value(value);
      }
    }
  }

  private final Collection<AppSecEvent> triggers;
  private String json;

  public AppSecEventWrapper(Collection<AppSecEvent> events) {
    this.triggers = events;
  }

  public Collection<AppSecEvent> getTriggers() {
    return triggers;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AppSecEventWrapper that = (AppSecEventWrapper) o;
    return Objects.equals(triggers, that.triggers);
  }

  @Override
  public int hashCode() {
    return triggers.hashCode();
  }

  @Override
  public String toString() {
    if (json == null) {
      json = ADAPTER.toJson(this);
    }
    return json;
  }
}
