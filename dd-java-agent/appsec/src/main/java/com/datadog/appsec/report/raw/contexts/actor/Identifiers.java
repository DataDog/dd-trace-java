package com.datadog.appsec.report.raw.contexts.actor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Identifiers {

  private final Map<String, Object> properties;

  protected Identifiers(Map<String, Object> properties) {
    this.properties = properties;
  }

  public Map<String, Object> getProperties() {
    return properties;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Identifiers that = (Identifiers) o;
    return properties.equals(that.properties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(properties);
  }

  public static class IdentifiersBuilder
      extends Identifiers.IdentifiersBuilderBase<IdentifiersBuilder> {
    @Override
    public Identifiers build() {
      return new Identifiers(map);
    }
  }

  public abstract static class IdentifiersBuilderBase<T extends IdentifiersBuilderBase<T>> {
    protected final Map<String, Object> map = new HashMap<>();

    protected abstract Identifiers build();

    public T withProperty(String name, Object value) {
      map.put(name, value);
      return (T) this;
    }
  }
}
