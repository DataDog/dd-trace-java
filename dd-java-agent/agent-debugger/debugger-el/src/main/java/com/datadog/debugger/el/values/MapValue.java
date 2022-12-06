package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.expressions.ValueExpression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A map-like {@linkplain Value}.<br>
 * Allows wrapping of {@linkplain Map} instances.
 */
public final class MapValue implements CollectionValue<MapValue>, ValueExpression<MapValue> {
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
      mapHolder = new HashMap<>((Map<?, ?>) object);
    } else if (object == null || object == Values.NULL_OBJECT) {
      mapHolder = Value.nullValue();
    } else {
      mapHolder = Value.undefinedValue();
    }
  }

  public boolean isEmpty() {
    if (mapHolder instanceof Map) {
      return ((Map<?, ?>) mapHolder).isEmpty();
    } else if (mapHolder instanceof Value) {
      Value<?> val = (Value<?>) mapHolder;
      return val.isNull() || val.isUndefined();
    }
    return true;
  }

  public int count() {
    if (mapHolder instanceof Map) {
      return ((Map<?, ?>) mapHolder).size();
    } else if (mapHolder == Value.nullValue()) {
      return 0;
    }
    return -1;
  }

  public Set<Value<?>> getKeys() {
    if (mapHolder instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) mapHolder;
      return map.keySet().stream().map(Value::of).collect(Collectors.toSet());
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
      Map<?, ?> map = (Map<?, ?>) mapHolder;
      key = key instanceof Value ? ((Value<?>) key).getValue() : key;
      Object value = map.containsKey(key) ? map.get(key) : Value.undefinedValue();
      return value != null ? Value.of(value) : Value.nullValue();
    }
    // the result will be either Value.nullValue() or Value.undefinedValue() depending on the holder
    // value
    return (Value<?>) mapHolder;
  }

  public boolean isNull() {
    return mapHolder == null || (mapHolder instanceof Value && ((Value<?>) mapHolder).isNull());
  }

  public boolean isUndefined() {
    return mapHolder instanceof Value && ((Value<?>) mapHolder).isUndefined();
  }

  @Override
  public MapValue getValue() {
    return this;
  }

  @Override
  public MapValue evaluate(ValueReferenceResolver valueRefResolver) {
    return this;
  }
}
