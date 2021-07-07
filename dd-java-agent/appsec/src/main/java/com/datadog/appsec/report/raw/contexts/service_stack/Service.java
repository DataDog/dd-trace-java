package com.datadog.appsec.report.raw.contexts.service_stack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Service {

  private final Map<String, Object> properties;

  protected Service(Map<String, Object> properties) {
    this.properties = properties;
  }

  public Map<String, Object> getProperties() {
    return properties;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Service that = (Service) o;
    return properties.equals(that.properties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(properties);
  }

  public static class ServiceBuilder extends ServiceBuilderBase<ServiceBuilder> {
    @Override
    public Service build() {
      return new Service(map);
    }
  }

  public abstract static class ServiceBuilderBase<T extends ServiceBuilderBase<T>> {
    protected final Map<String, Object> map = new HashMap<>();

    protected abstract Service build();

    public T withProperty(String name, Object value) {
      map.put(name, value);
      return (T) this;
    }
  }
}
