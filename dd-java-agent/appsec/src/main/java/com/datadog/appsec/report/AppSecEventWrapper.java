package com.datadog.appsec.report;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import java.util.Collection;
import java.util.Objects;

public class AppSecEventWrapper {

  private static final JsonAdapter<AppSecEventWrapper> ADAPTER =
      new Moshi.Builder().build().adapter(AppSecEventWrapper.class);

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
