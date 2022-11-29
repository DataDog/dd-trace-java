package com.datadog.debugger.el;

import com.datadog.debugger.el.values.ObjectValue;
import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.ValueReferences;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.HashMap;
import java.util.Map;

public class StaticValueRefResolver implements ValueReferenceResolver {
  private final Object self;

  private final Map<String, Object> valueMap;

  public StaticValueRefResolver(
      Object self, long duration, Object returnValue, Map<String, Object> values) {
    this.valueMap = values != null ? new HashMap<>(values) : new HashMap<>();
    this.self = self;
    if (duration != Long.MAX_VALUE) {
      this.valueMap.put(ValueReferences.DURATION_EXTENSION_NAME, duration);
    }
    if (returnValue != null) {
      this.valueMap.put(ValueReferences.RETURN_EXTENSION_NAME, returnValue);
    }
  }

  public static StaticValueRefResolver self(Object value) {
    return new StaticValueRefResolver(value, Long.MAX_VALUE, null, null);
  }

  private StaticValueRefResolver(StaticValueRefResolver other, Map<String, Object> extensions) {
    this.self = other.self;
    this.valueMap = new HashMap<>(other.valueMap);
    this.valueMap.putAll(extensions);
  }

  @Override
  public Object lookup(String name) {
    String rawName = name;
    if (name.startsWith("this.")) {
      rawName = name.substring(5);
    } else if (name.startsWith("@")) {
      rawName = name.substring(1);
    }
    Object target = ReflectiveFieldValueResolver.resolve(self, self.getClass(), rawName);
    if (target == Values.UNDEFINED_OBJECT) {
      target = valueMap.getOrDefault(rawName, Values.UNDEFINED_OBJECT);
    }
    return target;
  }

  @Override
  public Object getMember(Object target, String name) {
    if (target == Values.UNDEFINED_OBJECT) {
      return target;
    }
    if (target instanceof ObjectValue) {
      target = ((ObjectValue) target).getValue();
    }
    return ReflectiveFieldValueResolver.resolve(target, target.getClass(), name);
  }

  @Override
  public ValueReferenceResolver withExtensions(Map<String, Object> extensions) {
    return new StaticValueRefResolver(this, extensions);
  }
}
