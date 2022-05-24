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
  public Object resolve(String path) {
    boolean isField = false;
    String[] parts;
    if (path.startsWith(".")) {
      isField = true;
      parts = path.substring(1).split("\\.");
    } else if (path.startsWith("@")) {
      parts = path.substring(1).split("\\.");
    } else {
      parts = path.split("\\.");
    }
    Object target;
    if (isField) {
      target = ReflectiveFieldValueResolver.resolve(self, self.getClass(), parts[0]);
    } else {
      target = valueMap.containsKey(parts[0]) ? valueMap.get(parts[0]) : Values.UNDEFINED_OBJECT;
    }
    for (int i = 1; i < parts.length; i++) {
      if (target == Values.UNDEFINED_OBJECT) {
        break;
      }
      if (target instanceof ObjectValue) {
        target = ((ObjectValue) target).getValue();
      }
      target = ReflectiveFieldValueResolver.resolve(target, target.getClass(), parts[i]);
    }
    return target;
  }

  @Override
  public ValueReferenceResolver withExtensions(Map<String, Object> extensions) {
    return new StaticValueRefResolver(this, extensions);
  }
}
