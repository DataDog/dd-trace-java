package com.datadog.debugger.el.values;

import com.datadog.debugger.el.Value;
import datadog.trace.bootstrap.debugger.el.Values;

/**
 * A common interface for collection-like values (map or list)
 *
 * @param <T>
 */
public interface CollectionValue<T> extends Value<T> {
  CollectionValue<?> UNDEFINED =
      new CollectionValue<Object>() {
        @Override
        public boolean isEmpty() {
          return true;
        }

        @Override
        public int count() {
          return 0;
        }

        @Override
        public Value<?> get(Object key) {
          return Value.undefinedValue();
        }

        @Override
        public Object getValue() {
          return Values.UNDEFINED_OBJECT;
        }

        @Override
        public boolean isUndefined() {
          return true;
        }

        @Override
        public boolean isNull() {
          return false;
        }
      };

  CollectionValue<?> NULL =
      new CollectionValue<Object>() {
        @Override
        public boolean isEmpty() {
          return true;
        }

        @Override
        public int count() {
          return -1;
        }

        @Override
        public Value<?> get(Object key) {
          return Value.nullValue();
        }

        @Override
        public Object getValue() {
          return Value.nullValue();
        }

        @Override
        public boolean isUndefined() {
          return false;
        }

        @Override
        public boolean isNull() {
          return true;
        }
      };

  boolean isEmpty();

  int count();

  Value<?> get(Object key);
}
