package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.ValueExpression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A map-like {@linkplain Value}.<br>
 * Allows wrapping of {@linkplain Map} instances.
 */
public final class MapValue implements CollectionValue<Object>, ValueExpression<MapValue> {
  private static final Logger log = LoggerFactory.getLogger(MapValue.class);

  public static final class Entry {
    final Value<?> key;
    final Value<?> value;

    public Entry(Value<?> key, Value<?> value) {
      this.key = key;
      this.value = value;
    }
  }

  private final Object mapHolder;

  public MapValue(Object object) {
    if (object instanceof Map) {
      mapHolder = object;
    } else if (object == null || object == Values.NULL_OBJECT) {
      mapHolder = Value.nullValue();
    } else {
      mapHolder = Value.undefinedValue();
    }
  }

  public boolean isEmpty() {
    if (mapHolder instanceof Map) {
      if (WellKnownClasses.isSafe((Map<?, ?>) mapHolder)) {
        return ((Map<?, ?>) mapHolder).isEmpty();
      }
      throw new UnsupportedOperationException(
          "Unsupported Map class: " + mapHolder.getClass().getTypeName());
    } else if (mapHolder instanceof Value) {
      Value<?> val = (Value<?>) mapHolder;
      return val.isNull() || val.isUndefined();
    }
    return true;
  }

  public int count() {
    if (mapHolder instanceof Map) {
      if (WellKnownClasses.isSafe((Map<?, ?>) mapHolder)) {
        return ((Map<?, ?>) mapHolder).size();
      }
      throw new UnsupportedOperationException(
          "Unsupported Map class: " + mapHolder.getClass().getTypeName());
    } else if (mapHolder == Value.nullValue()) {
      return 0;
    }
    return -1;
  }

  public Set<Value<?>> getKeys() {
    if (mapHolder instanceof Map) {
      if (WellKnownClasses.isSafe((Map<?, ?>) mapHolder)) {
        Map<?, ?> map = (Map<?, ?>) mapHolder;
        return map.keySet().stream().map(Value::of).collect(Collectors.toSet());
      }
      throw new UnsupportedOperationException(
          "Unsupported Map class: " + mapHolder.getClass().getTypeName());
    }
    log.warn("{} is not a map", mapHolder);
    return Collections.singleton(Value.undefinedValue());
  }

  public Value<?> get(Object key) {
    if (key == Value.undefinedValue() || key == Values.UNDEFINED_OBJECT) {
      return Value.undefinedValue();
    }
    if (key == null || key == Value.nullValue() || key == Values.NULL_OBJECT) {
      return Value.nullValue();
    }

    if (mapHolder instanceof Map) {
      if (WellKnownClasses.isSafe((Map<?, ?>) mapHolder)) {
        Map<?, ?> map = (Map<?, ?>) mapHolder;
        key = key instanceof Value ? ((Value<?>) key).getValue() : key;
        Object value = map.get(key);
        return value != null ? Value.of(value) : Value.nullValue();
      }
      throw new UnsupportedOperationException(
          "Unsupported Map class: " + mapHolder.getClass().getTypeName());
    }
    // the result will be either Value.nullValue() or Value.undefinedValue() depending on the holder
    // value
    return (Value<?>) mapHolder;
  }

  @Override
  public boolean contains(Value<?> val) {
    if (mapHolder instanceof Map) {
      if (WellKnownClasses.isSafe((Map<?, ?>) mapHolder)) {
        Map<?, ?> map = (Map<?, ?>) mapHolder;
        if (val == null || val.isNull()) {
          return map.containsKey(null);
        }
        if (WellKnownClasses.isEqualsSafe(val.getValue().getClass())) {
          return map.containsKey(val.getValue());
        }
        throw new UnsupportedOperationException(
            "Unsupported key class: " + val.getValue().getClass().getTypeName());
      }
      throw new UnsupportedOperationException(
          "Unsupported Map class: " + mapHolder.getClass().getTypeName());
    }
    return false;
  }

  @Override
  public boolean isNull() {
    return mapHolder == null || (mapHolder instanceof Value && ((Value<?>) mapHolder).isNull());
  }

  @Override
  public boolean isUndefined() {
    return mapHolder instanceof Value && ((Value<?>) mapHolder).isUndefined();
  }

  @Override
  public Object getValue() {
    return mapHolder;
  }

  @Override
  public MapValue evaluate(ValueReferenceResolver valueRefResolver) {
    return this;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public Object getMapHolder() {
    return mapHolder;
  }
}
