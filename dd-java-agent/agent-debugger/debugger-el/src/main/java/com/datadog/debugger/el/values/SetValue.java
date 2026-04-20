package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.ValueType;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.ValueExpression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.util.Collection;
import java.util.Set;

public class SetValue implements CollectionValue<Object>, ValueExpression<SetValue> {

  private final Object setHolder;

  public SetValue(Object object) {
    if (object instanceof Set) {
      setHolder = object;
    } else if (object == null || object == Values.NULL_OBJECT) {
      setHolder = Value.nullValue();
    } else {
      setHolder = Value.undefinedValue();
    }
  }

  @Override
  public SetValue evaluate(ValueReferenceResolver valueRefResolver) {
    return this;
  }

  @Override
  public Object getValue() {
    return setHolder;
  }

  @Override
  public boolean isEmpty() {
    if (setHolder instanceof Set) {
      if (WellKnownClasses.isSafe((Collection<?>) setHolder)) {
        return ((Set<?>) setHolder).isEmpty();
      }
      throw new UnsupportedOperationException(
          "Unsupported Set class: " + setHolder.getClass().getTypeName());
    } else if (setHolder instanceof Value) {
      Value<?> val = (Value<?>) setHolder;
      return val.isNull() || val.isUndefined();
    }
    return true;
  }

  @Override
  public int count() {
    if (setHolder instanceof Set) {
      if (WellKnownClasses.isSafe((Collection<?>) setHolder)) {
        return ((Set<?>) setHolder).size();
      }
      throw new UnsupportedOperationException(
          "Unsupported Set class: " + setHolder.getClass().getTypeName());
    } else if (setHolder == Value.nullValue()) {
      return 0;
    }
    return -1;
  }

  @Override
  public Value<?> get(Object key) {
    if (key == Value.undefinedValue() || key == Values.UNDEFINED_OBJECT) {
      return Value.undefinedValue();
    }
    if (key == null || key == Value.nullValue() || key == Values.NULL_OBJECT) {
      return Value.nullValue();
    }

    if (setHolder instanceof Set) {
      if (WellKnownClasses.isSafe((Collection<?>) setHolder)) {
        Set<?> set = (Set<?>) setHolder;
        key = key instanceof Value ? ((Value<?>) key).getValue() : key;
        return Value.of(set.contains(key), ValueType.BOOLEAN);
      }
      throw new UnsupportedOperationException(
          "Unsupported Set class: " + setHolder.getClass().getTypeName());
    }
    // the result will be either Value.nullValue() or Value.undefinedValue() depending on the holder
    // value
    return (Value<?>) setHolder;
  }

  @Override
  public boolean contains(Value<?> val) {
    if (setHolder instanceof Set) {
      if (WellKnownClasses.isSafe((Collection<?>) setHolder)) {
        if (val == null || val.isNull()) {
          return ((Set<?>) setHolder).contains(null);
        }
        if (WellKnownClasses.isEqualsSafe(val.getValue().getClass())) {
          return ((Set<?>) setHolder).contains(val.getValue());
        }
        throw new UnsupportedOperationException(
            "Unsupported value class: " + val.getValue().getClass().getTypeName());
      }
      throw new UnsupportedOperationException(
          "Unsupported Set class: " + setHolder.getClass().getTypeName());
    }
    return false;
  }

  @Override
  public boolean isNull() {
    return setHolder == null || (setHolder instanceof Value && ((Value<?>) setHolder).isNull());
  }

  @Override
  public boolean isUndefined() {
    return setHolder instanceof Value && ((Value<?>) setHolder).isUndefined();
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public Object getSetHolder() {
    return setHolder;
  }
}
