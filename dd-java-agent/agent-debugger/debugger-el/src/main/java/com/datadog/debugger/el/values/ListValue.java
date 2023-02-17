package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Value;
import com.datadog.debugger.el.Visitor;
import com.datadog.debugger.el.expressions.ValueExpression;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import datadog.trace.bootstrap.debugger.el.Values;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A list-like {@linkplain Value}.<br>
 * Allows wrapping of arrays as well as {@linkplain List} or {@link Set} instances.
 */
public class ListValue implements CollectionValue<Object>, ValueExpression<ListValue> {
  private final Object listHolder;
  private final Object arrayHolder;
  private final Class<?> arrayType;

  public ListValue(Object object) {
    if (object == null || object == Values.NULL_OBJECT || object == Value.nullValue()) {
      listHolder = Value.nullValue();
      arrayHolder = null;
      arrayType = null;
    } else if (object == Values.UNDEFINED_OBJECT || object == Value.undefinedValue()) {
      listHolder = Value.undefinedValue();
      arrayHolder = null;
      arrayType = null;
    } else if (object instanceof Collection) {
      listHolder = object;
      arrayHolder = null;
      arrayType = null;
    } else if (object.getClass().isArray()) {
      listHolder = null;
      arrayHolder = object;
      arrayType = object.getClass().getComponentType();
    } else {
      listHolder = Value.undefinedValue();
      arrayHolder = null;
      arrayType = null;
    }
  }

  public boolean isNull() {
    return (listHolder == null || (listHolder instanceof Value && ((Value<?>) listHolder).isNull()))
        && arrayHolder == null;
  }

  public boolean isUndefined() {
    return listHolder instanceof Value && ((Value<?>) listHolder).isUndefined();
  }

  public boolean isEmpty() {
    if (listHolder instanceof Collection) {
      return ((Collection<?>) listHolder).isEmpty();
    } else if (listHolder instanceof Value) {
      Value<?> val = (Value<?>) listHolder;
      return val.isNull() || val.isUndefined();
    }
    if (arrayHolder != null) {
      return count() == 0;
    }
    return true;
  }

  public int count() {
    if (listHolder instanceof Collection) {
      return ((Collection<?>) listHolder).size();
    } else if (listHolder == Value.nullValue()) {
      return 0;
    } else if (arrayHolder != null) {
      return Array.getLength(arrayHolder);
    }
    return -1;
  }

  public Value<?> get(Object key) {
    if (key instanceof Integer) {
      return get((int) key);
    }
    if (key instanceof Long) {
      return get(((Long) key).intValue());
    }
    return Value.undefinedValue();
  }

  public Value<?> get(int index) {
    int len = count();
    if (index < 0 || index >= len) {
      throw new IllegalArgumentException("index[" + index + "] out of bounds: [0-" + len + "]");
    }
    if (listHolder instanceof List) {
      return Value.of(((List<?>) listHolder).get(index));
    } else if (listHolder instanceof Set) {
      throw new UnsupportedOperationException("Cannot access Set by index");
    } else if (listHolder instanceof Value) {
      return (Value<?>) listHolder;
    } else if (arrayHolder != null) {
      if (arrayType.isPrimitive()) {
        if (arrayType == byte.class) {
          return Value.of(Array.getByte(arrayHolder, index));
        } else if (arrayType == char.class) {
          return Value.of(Array.getChar(arrayHolder, index));
        } else if (arrayType == short.class) {
          return Value.of(Array.getShort(arrayHolder, index));
        } else if (arrayType == int.class) {
          return Value.of(Array.getInt(arrayHolder, index));
        } else if (arrayType == long.class) {
          return Value.of(Array.getLong(arrayHolder, index));
        } else if (arrayType == float.class) {
          return Value.of(Array.getFloat(arrayHolder, index));
        } else if (arrayType == double.class) {
          return Value.of(Array.getDouble(arrayHolder, index));
        } else if (arrayType == boolean.class) {
          return Value.of(Array.getBoolean(arrayHolder, index));
        }
      } else {
        return Value.of(Array.get(arrayHolder, index));
      }
    }
    return Value.undefinedValue();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ListValue listValue = (ListValue) o;
    return Objects.equals(listHolder, listValue.listHolder)
        && Objects.equals(arrayHolder, listValue.arrayHolder)
        && Objects.equals(arrayType, listValue.arrayType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(listHolder, arrayHolder, arrayType);
  }

  @Override
  public Object getValue() {
    if (arrayHolder != null) {
      return arrayHolder;
    }
    return listHolder;
  }

  @Override
  public ListValue evaluate(ValueReferenceResolver valueRefResolver) {
    return this;
  }

  @Override
  public <R> R accept(Visitor<R> visitor) {
    return visitor.visit(this);
  }

  public Object getListHolder() {
    return listHolder;
  }

  public Object getArrayHolder() {
    return arrayHolder;
  }

  public Class<?> getArrayType() {
    return arrayType;
  }
}
