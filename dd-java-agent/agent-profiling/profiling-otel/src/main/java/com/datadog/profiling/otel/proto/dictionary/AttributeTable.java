package com.datadog.profiling.otel.proto.dictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Attribute deduplication table for OTLP profiles. Index 0 is reserved for the null/unset
 * attribute. Attributes are key-value pairs with optional unit.
 */
public final class AttributeTable {

  /** Attribute value types. */
  public enum ValueType {
    STRING,
    BOOL,
    INT,
    DOUBLE
  }

  /** Immutable key for attribute lookup. */
  private static final class AttributeKey {
    final int keyIndex;
    final ValueType valueType;
    final Object value;
    final int unitIndex;

    AttributeKey(int keyIndex, ValueType valueType, Object value, int unitIndex) {
      this.keyIndex = keyIndex;
      this.valueType = valueType;
      this.value = value;
      this.unitIndex = unitIndex;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      AttributeKey that = (AttributeKey) o;
      return keyIndex == that.keyIndex
          && unitIndex == that.unitIndex
          && valueType == that.valueType
          && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(keyIndex, valueType, value, unitIndex);
    }
  }

  /** Attribute entry stored in the table. */
  public static final class AttributeEntry {
    public final int keyIndex;
    public final ValueType valueType;
    public final Object value;
    public final int unitIndex;

    AttributeEntry(int keyIndex, ValueType valueType, Object value, int unitIndex) {
      this.keyIndex = keyIndex;
      this.valueType = valueType;
      this.value = value;
      this.unitIndex = unitIndex;
    }

    public String getStringValue() {
      return valueType == ValueType.STRING ? (String) value : null;
    }

    public Boolean getBoolValue() {
      return valueType == ValueType.BOOL ? (Boolean) value : null;
    }

    public Long getIntValue() {
      return valueType == ValueType.INT ? (Long) value : null;
    }

    public Double getDoubleValue() {
      return valueType == ValueType.DOUBLE ? (Double) value : null;
    }
  }

  private final List<AttributeEntry> attributes;
  private final Map<AttributeKey, Integer> attributeToIndex;

  public AttributeTable() {
    attributes = new ArrayList<>();
    attributeToIndex = new HashMap<>();
    // Index 0 is reserved for null/unset attribute
    attributes.add(new AttributeEntry(0, ValueType.STRING, "", 0));
  }

  public int internString(int keyIndex, String value, int unitIndex) {
    if (keyIndex == 0) {
      return 0;
    }
    return intern(keyIndex, ValueType.STRING, value, unitIndex);
  }

  public int internBool(int keyIndex, boolean value, int unitIndex) {
    if (keyIndex == 0) {
      return 0;
    }
    return intern(keyIndex, ValueType.BOOL, value, unitIndex);
  }

  public int internInt(int keyIndex, long value, int unitIndex) {
    if (keyIndex == 0) {
      return 0;
    }
    return intern(keyIndex, ValueType.INT, value, unitIndex);
  }

  public int internDouble(int keyIndex, double value, int unitIndex) {
    if (keyIndex == 0) {
      return 0;
    }
    return intern(keyIndex, ValueType.DOUBLE, value, unitIndex);
  }

  private int intern(int keyIndex, ValueType valueType, Object value, int unitIndex) {
    AttributeKey key = new AttributeKey(keyIndex, valueType, value, unitIndex);
    Integer existing = attributeToIndex.get(key);
    if (existing != null) {
      return existing;
    }

    int index = attributes.size();
    attributes.add(new AttributeEntry(keyIndex, valueType, value, unitIndex));
    attributeToIndex.put(key, index);
    return index;
  }

  public AttributeEntry get(int index) {
    return attributes.get(index);
  }

  public int size() {
    return attributes.size();
  }

  public List<AttributeEntry> getAttributes() {
    return attributes;
  }

  /** Resets the table to its initial state with only the null attribute at index 0. */
  public void reset() {
    attributes.clear();
    attributeToIndex.clear();
    attributes.add(new AttributeEntry(0, ValueType.STRING, "", 0));
  }
}
