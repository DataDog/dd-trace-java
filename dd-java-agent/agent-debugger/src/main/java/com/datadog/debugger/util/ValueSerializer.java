package com.datadog.debugger.util;

import datadog.trace.bootstrap.debugger.Limits;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** serialize Java Object value with applied {@link Limits} and following references */
public class ValueSerializer {

  public static boolean isPrimitive(String type) {
    switch (type) {
      case "byte":
      case "short":
      case "char":
      case "int":
      case "long":
      case "boolean":
      case "float":
      case "double":
      case "java.lang.Byte":
      case "java.lang.Short":
      case "java.lang.Character":
      case "java.lang.Integer":
      case "java.lang.Long":
      case "java.lang.Boolean":
      case "java.lang.Float":
      case "java.lang.Double":
      case "String":
      case "java.lang.String":
        return true;
    }
    return false;
  }

  public interface TypeSerializer {
    void prologue(Object value, String type) throws Exception;

    void epilogue(Object value) throws Exception;

    void nullValue() throws Exception;

    void string(String value, boolean isComplete, int originalLength) throws Exception;

    void primitiveValue(Object value) throws Exception;

    void arrayPrologue(Object value) throws Exception;

    void arrayEpilogue(Object value, boolean isComplete, int arraySize) throws Exception;

    void primitiveArrayElement(String value, String type) throws Exception;

    void collectionPrologue(Object value) throws Exception;

    void collectionEpilogue(Object value, boolean isComplete, int size) throws Exception;

    void mapPrologue(Object value) throws Exception;

    void mapEntryPrologue(Map.Entry<?, ?> entry) throws Exception;

    void mapEntryEpilogue(Map.Entry<?, ?> entry) throws Exception;

    void mapEpilogue(Map<?, ?> map, boolean isComplete) throws Exception;

    void objectValue(Object value, ValueSerializer valueSerializer, Limits limits) throws Exception;

    void reachedMaxDepth() throws Exception;
  }

  private final TypeSerializer typeSerializer;

  public ValueSerializer(TypeSerializer typeSerializer) {
    this.typeSerializer = typeSerializer;
  }

  public void serialize(Object value, String type, Limits limits) throws Exception {
    typeSerializer.prologue(value, type);
    if (value == null) {
      typeSerializer.nullValue();
    } else if (isPrimitive(type) || WellKnownClasses.isToStringSafe(type)) {
      if (value instanceof String) {
        String strValue = (String) value;
        int originalLength = strValue.length();
        boolean isComplete = true;
        if (originalLength > limits.maxLength) {
          strValue = strValue.substring(0, limits.maxLength);
          isComplete = false;
        }
        typeSerializer.string(strValue, isComplete, originalLength);
      } else {
        typeSerializer.primitiveValue(value);
      }
    } else if (value.getClass().isArray() && (limits.maxReferenceDepth > 0)) {
      int arraySize = Array.getLength(value);
      boolean isComplete = true;
      typeSerializer.arrayPrologue(value);
      if (value.getClass().getComponentType().isPrimitive()) {
        Class<?> componentType = value.getClass().getComponentType();
        if (componentType == long.class) {
          isComplete = serializeLongArray((long[]) value, limits.maxCollectionSize);
        }
        if (componentType == int.class) {
          isComplete = serializeIntArray((int[]) value, limits.maxCollectionSize);
        }
        if (componentType == short.class) {
          isComplete = serializeShortArray((short[]) value, limits.maxCollectionSize);
        }
        if (componentType == char.class) {
          isComplete = serializeCharArray((char[]) value, limits.maxCollectionSize);
        }
        if (componentType == byte.class) {
          isComplete = serializeByteArray((byte[]) value, limits.maxCollectionSize);
        }
        if (componentType == boolean.class) {
          isComplete = serializeBooleanArray((boolean[]) value, limits.maxCollectionSize);
        }
        if (componentType == float.class) {
          isComplete = serializeFloatArray((float[]) value, limits.maxCollectionSize);
        }
        if (componentType == double.class) {
          isComplete = serializeDoubleArray((double[]) value, limits.maxCollectionSize);
        }
      } else {
        isComplete = serializeObjectArray((Object[]) value, limits);
      }
      typeSerializer.arrayEpilogue(value, isComplete, arraySize);
    } else if (value instanceof Collection && (limits.maxReferenceDepth > 0)) {
      typeSerializer.collectionPrologue(value);
      Collection<?> col = (Collection<?>) value;
      boolean isComplete = serializeCollection(col, limits);
      typeSerializer.collectionEpilogue(value, isComplete, col.size());
    } else if (value instanceof Map && (limits.maxReferenceDepth > 0)) {
      typeSerializer.mapPrologue(value);
      Map<?, ?> map = (Map<?, ?>) value;
      Set<? extends Map.Entry<?, ?>> entries = map.entrySet();
      boolean isComplete = serializeMap(entries, limits);
      typeSerializer.mapEpilogue(map, isComplete);
    } else if (limits.maxReferenceDepth > 0) {
      typeSerializer.objectValue(value, this, limits);
    } else {
      typeSerializer.reachedMaxDepth();
    }
    typeSerializer.epilogue(value);
  }

  private boolean serializeLongArray(long[] longArray, int maxSize) throws Exception {
    maxSize = Math.min(longArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      long val = longArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "long");
      i++;
    }
    return maxSize == longArray.length;
  }

  private boolean serializeIntArray(int[] intArray, int maxSize) throws Exception {
    maxSize = Math.min(intArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      long val = intArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "int");
      i++;
    }
    return maxSize == intArray.length;
  }

  private boolean serializeShortArray(short[] shortArray, int maxSize) throws Exception {
    maxSize = Math.min(shortArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      short val = shortArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "short");
      i++;
    }
    return maxSize == shortArray.length;
  }

  private boolean serializeCharArray(char[] charArray, int maxSize) throws Exception {
    maxSize = Math.min(charArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      char val = charArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "char");
      i++;
    }
    return maxSize == charArray.length;
  }

  private boolean serializeByteArray(byte[] byteArray, int maxSize) throws Exception {
    maxSize = Math.min(byteArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      byte val = byteArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "byte");
      i++;
    }
    return maxSize == byteArray.length;
  }

  private boolean serializeBooleanArray(boolean[] booleanArray, int maxSize) throws Exception {
    maxSize = Math.min(booleanArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      boolean val = booleanArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "boolean");
      i++;
    }
    return maxSize == booleanArray.length;
  }

  private boolean serializeFloatArray(float[] floatArray, int maxSize) throws Exception {
    maxSize = Math.min(floatArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      float val = floatArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "float");
      i++;
    }
    return maxSize == floatArray.length;
  }

  private boolean serializeDoubleArray(double[] doubleArray, int maxSize) throws Exception {
    maxSize = Math.min(doubleArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      double val = doubleArray[i];
      String strVal = String.valueOf(val);
      typeSerializer.primitiveArrayElement(strVal, "double");
      i++;
    }
    return maxSize == doubleArray.length;
  }

  private boolean serializeObjectArray(Object[] objArray, Limits limits) throws Exception {
    int maxSize = Math.min(objArray.length, limits.maxCollectionSize);
    Limits newLimits = Limits.decDepthLimits(limits.maxReferenceDepth, limits);
    int i = 0;
    while (i < maxSize) {
      Object val = objArray[i];
      serialize(val, val != null ? val.getClass().getTypeName() : "java.lang.Object", newLimits);
      i++;
    }
    return maxSize == objArray.length;
  }

  private boolean serializeCollection(Collection<?> collection, Limits limits) throws Exception {
    // /!\ here we assume that Collection#Size is O(1) /!\
    int colSize = collection.size();
    int maxSize = Math.min(colSize, limits.maxCollectionSize);
    Limits newLimits = Limits.decDepthLimits(limits.maxReferenceDepth, limits);
    int i = 0;
    Iterator<?> it = collection.iterator();
    while (i < maxSize && it.hasNext()) {
      Object val = it.next();
      serialize(val, val.getClass().getTypeName(), newLimits);
      i++;
    }
    return maxSize == colSize;
  }

  private boolean serializeMap(Set<? extends Map.Entry<?, ?>> entries, Limits limits)
      throws Exception {
    int mapSize = entries.size();
    int maxSize = Math.min(mapSize, limits.maxCollectionSize);
    Limits newLimits = Limits.decDepthLimits(limits.maxReferenceDepth, limits);
    int i = 0;
    Iterator<?> it = entries.iterator();
    while (i < maxSize && it.hasNext()) {
      Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next();
      typeSerializer.mapEntryPrologue(entry);
      Object keyObj = entry.getKey();
      Object valObj = entry.getValue();
      serialize(keyObj, keyObj.getClass().getTypeName(), newLimits);
      serialize(valObj, valObj.getClass().getTypeName(), newLimits);
      typeSerializer.mapEntryEpilogue(entry);
      i++;
    }
    return maxSize == mapSize;
  }
}
