package com.datadog.debugger.util;

import static datadog.trace.bootstrap.debugger.util.Redaction.REDACTED_VALUE;

import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.el.ReflectiveFieldValueResolver;
import datadog.trace.bootstrap.debugger.util.Redaction;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import datadog.trace.bootstrap.debugger.util.WellKnownClasses;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** serialize Java Object value with applied {@link Limits} and following references */
public class SerializerWithLimits {
  private static final Logger LOG = LoggerFactory.getLogger(SerializerWithLimits.class);

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

  enum NotCapturedReason {
    MAX_DEPTH,
    FIELD_COUNT,
    TIMEOUT,
    REDACTED_IDENT,
    REDACTED_TYPE
  }

  public interface TokenWriter {
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

    void mapEpilogue(boolean isComplete, int size) throws Exception;

    void objectPrologue(Object value) throws Exception;

    default boolean objectFilterInField(Field field) throws Exception {
      // Jacoco insert a transient field
      if ("$jacocoData".equals(field.getName()) && Modifier.isTransient(field.getModifiers())) {
        return false;
      }
      // skip static fields
      if (Modifier.isStatic(field.getModifiers())) {
        return false;
      }
      return true;
    }

    void objectFieldPrologue(String fieldName, Object value, int maxDepth) throws Exception;

    void handleFieldException(Exception ex, Field field);

    void fieldNotCaptured(String reason, Field field);

    void objectEpilogue(Object value) throws Exception;

    void notCaptured(NotCapturedReason reason) throws Exception;

    void notCaptured(String reason) throws Exception;
  }

  private final TokenWriter tokenWriter;
  private final TimeoutChecker timeoutChecker;
  private RuntimeException exception;

  public SerializerWithLimits(TokenWriter tokenWriter, TimeoutChecker timeoutChecker) {
    this.tokenWriter = tokenWriter;
    this.timeoutChecker = timeoutChecker;
  }

  public void serialize(Object value, String type, Limits limits) throws Exception {
    if (type == null) {
      throw new IllegalArgumentException("Type is required for serialization");
    }
    tokenWriter.prologue(value, type);
    NotCapturedReason reason = null;
    if (value == REDACTED_VALUE) {
      reason = NotCapturedReason.REDACTED_IDENT;
    } else if (Redaction.isRedactedType(type)) {
      reason = NotCapturedReason.REDACTED_TYPE;
    }
    if (reason != null) {
      tokenWriter.notCaptured(reason);
      tokenWriter.epilogue(value);
      return;
    }
    if (timeoutChecker.isTimedOut(System.currentTimeMillis())) {
      tokenWriter.notCaptured(NotCapturedReason.TIMEOUT);
      tokenWriter.epilogue(value);
      return;
    }
    if (value == null) {
      tokenWriter.nullValue();
    } else if (isPrimitive(type) || WellKnownClasses.isToStringSafe(type)) {
      serializePrimitive(value, limits);
    } else if (value.getClass().isArray() && (limits.maxReferenceDepth > 0)) {
      serializeArray(value, limits);
    } else if (value instanceof Collection && (limits.maxReferenceDepth > 0)) {
      if (WellKnownClasses.isSafe((Collection<?>) value)) {
        serializeCollection(value, limits);
      } else {
        serializeObjectValue(value, limits);
      }
    } else if (value instanceof Map && (limits.maxReferenceDepth > 0)) {
      if (WellKnownClasses.isSafe((Map<?, ?>) value)) {
        serializeMap(value, limits);
      } else {
        serializeObjectValue(value, limits);
      }
    } else if (value instanceof Enum) {
      serializeEnum(value, limits);
    } else if (limits.maxReferenceDepth > 0) {
      serializeObjectValue(value, limits);
    } else {
      tokenWriter.notCaptured(NotCapturedReason.MAX_DEPTH);
    }
    tokenWriter.epilogue(value);
  }

  private void serializeEnum(Object value, Limits limits) throws Exception {
    Enum<?> enumValue = (Enum<?>) value;
    serializePrimitive(enumValue.name(), limits);
  }

  private void serializeMap(Object value, Limits limits) throws Exception {
    tokenWriter.mapPrologue(value);
    Map<?, ?> map;
    boolean isComplete = true;
    int size = 0;
    try {
      map = (Map<?, ?>) value;
      size = map.size(); // /!\ alien call /!\
      Set<? extends Map.Entry<?, ?>> entries = map.entrySet(); // /!\ alien call /!\
      isComplete = serializeMapEntries(entries, limits); // /!\ contains alien calls /!\
      tokenWriter.mapEpilogue(isComplete, size);
    } catch (Exception ex) {
      tokenWriter.mapEpilogue(isComplete, size);
      tokenWriter.notCaptured(ex.toString());
    }
  }

  private void serializeCollection(Object value, Limits limits) throws Exception {
    tokenWriter.collectionPrologue(value);
    Collection<?> col;
    boolean isComplete = true;
    int size = 0;
    try {
      col = (Collection<?>) value;
      size = col.size(); // /!\ alien call /!\
      isComplete = serializeCollection(col, limits); // /!\ contains alien calls /!\
      tokenWriter.collectionEpilogue(value, isComplete, size);
    } catch (Exception ex) {
      tokenWriter.collectionEpilogue(value, isComplete, size);
      tokenWriter.notCaptured(ex.toString());
    }
  }

  private void serializeArray(Object value, Limits limits) throws Exception {
    int arraySize = Array.getLength(value);
    boolean isComplete = true;
    tokenWriter.arrayPrologue(value);
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
    tokenWriter.arrayEpilogue(value, isComplete, arraySize);
  }

  private void serializePrimitive(Object value, Limits limits) throws Exception {
    if (value instanceof String) {
      String strValue = (String) value;
      int originalLength = strValue.length();
      boolean isComplete = true;
      if (originalLength > limits.maxLength) {
        strValue = strValue.substring(0, limits.maxLength);
        isComplete = false;
      }
      tokenWriter.string(strValue, isComplete, originalLength);
    } else {
      tokenWriter.primitiveValue(value);
    }
  }

  private void serializeObjectValue(Object value, Limits limits) throws Exception {
    tokenWriter.objectPrologue(value);
    Map<String, Function<Object, CapturedContext.CapturedValue>> specialTypeAccess =
        WellKnownClasses.getSpecialTypeAccess(value);
    Class<?> currentClass = value.getClass();
    int processedFieldCount = 0;
    NotCapturedReason reason = null;
    classLoop:
    do {
      Field[] fields = currentClass.getDeclaredFields();
      for (Field field : fields) {
        try {
          if (processedFieldCount >= limits.maxFieldCount) {
            reason = NotCapturedReason.FIELD_COUNT;
            break classLoop;
          }
          if (!tokenWriter.objectFilterInField(field)) {
            continue;
          }
          processedFieldCount++;
          if (specialTypeAccess != null) {
            Function<Object, CapturedContext.CapturedValue> specialFieldAccess =
                specialTypeAccess.get(field.getName());
            if (specialFieldAccess != null) {
              onSpecialField(specialFieldAccess, value, limits);
            } else {
              onField(field, value, limits);
            }
          } else {
            onField(field, value, limits);
          }
        } catch (Exception e) {
          tokenWriter.handleFieldException(e, field);
        }
      }
    } while ((currentClass = currentClass.getSuperclass()) != null);
    tokenWriter.objectEpilogue(value);
    if (reason != null) {
      tokenWriter.notCaptured(reason);
    }
  }

  private void onField(Field field, Object obj, Limits limits) throws Exception {
    if (ReflectiveFieldValueResolver.trySetAccessible(field)) {
      field.setAccessible(true);
      Object fieldValue = field.get(obj);
      internalOnField(field.getName(), field.getType().getTypeName(), fieldValue, limits);
    } else {
      String msg = ReflectiveFieldValueResolver.buildInaccessibleMsg(field);
      tokenWriter.fieldNotCaptured(msg, field);
    }
  }

  private void onSpecialField(
      Function<Object, CapturedContext.CapturedValue> specialFieldAccess,
      Object value,
      Limits limits)
      throws Exception {
    CapturedContext.CapturedValue field = specialFieldAccess.apply(value);
    internalOnField(field.getName(), field.getType(), field.getValue(), limits);
  }

  private void internalOnField(String fieldName, String fieldType, Object value, Limits limits)
      throws Exception {
    tokenWriter.objectFieldPrologue(fieldName, value, limits.maxReferenceDepth);
    Limits newLimits = Limits.decDepthLimits(limits);
    String typeName;
    if (SerializerWithLimits.isPrimitive(fieldType)) {
      typeName = fieldType;
    } else {
      typeName = value != null ? value.getClass().getTypeName() : fieldType;
    }
    if (Redaction.isRedactedKeyword(fieldName)) {
      value = REDACTED_VALUE;
    }
    serialize(
        value instanceof CapturedContext.CapturedValue
            ? ((CapturedContext.CapturedValue) value).getValue()
            : value,
        typeName,
        newLimits);
  }

  private boolean serializeLongArray(long[] longArray, int maxSize) throws Exception {
    maxSize = Math.min(longArray.length, maxSize);
    int i = 0;
    while (i < maxSize) {
      long val = longArray[i];
      String strVal = String.valueOf(val);
      tokenWriter.primitiveArrayElement(strVal, "long");
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
      tokenWriter.primitiveArrayElement(strVal, "int");
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
      tokenWriter.primitiveArrayElement(strVal, "short");
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
      tokenWriter.primitiveArrayElement(strVal, "char");
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
      tokenWriter.primitiveArrayElement(strVal, "byte");
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
      tokenWriter.primitiveArrayElement(strVal, "boolean");
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
      tokenWriter.primitiveArrayElement(strVal, "float");
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
      tokenWriter.primitiveArrayElement(strVal, "double");
      i++;
    }
    return maxSize == doubleArray.length;
  }

  private boolean serializeObjectArray(Object[] objArray, Limits limits) throws Exception {
    int maxSize = Math.min(objArray.length, limits.maxCollectionSize);
    Limits newLimits = Limits.decDepthLimits(limits);
    int i = 0;
    while (i < maxSize) {
      Object val = objArray[i];
      serialize(
          val, val != null ? val.getClass().getTypeName() : Object.class.getTypeName(), newLimits);
      i++;
    }
    return maxSize == objArray.length;
  }

  private boolean serializeCollection(Collection<?> collection, Limits limits) throws Exception {
    // /!\ here we assume that Collection#Size is O(1) /!\
    int colSize = collection.size(); // /!\ alien call /!\
    int maxSize = Math.min(colSize, limits.maxCollectionSize);
    Limits newLimits = Limits.decDepthLimits(limits);
    int i = 0;
    Iterator<?> it = collection.iterator(); // /!\ alien call /!\
    while (i < maxSize && it.hasNext()) { // /!\ alien call /!\
      Object val = it.next(); // /!\ alien call /!\
      serialize(
          val, val != null ? val.getClass().getTypeName() : Object.class.getTypeName(), newLimits);
      i++;
    }
    return maxSize == colSize;
  }

  private boolean serializeMapEntries(Set<? extends Map.Entry<?, ?>> entries, Limits limits)
      throws Exception {
    int mapSize = entries.size(); // /!\ alien call /!\
    int maxSize = Math.min(mapSize, limits.maxCollectionSize);
    Limits newLimits = Limits.decDepthLimits(limits);
    int i = 0;
    Iterator<?> it = entries.iterator(); // /!\ alien call /!\
    while (i < maxSize && it.hasNext()) { // /!\ alien call /!\
      Map.Entry<?, ?> entry = (Map.Entry<?, ?>) it.next(); // /!\ alien call /!\
      tokenWriter.mapEntryPrologue(entry);
      Object keyObj = entry.getKey(); // /!\ alien call /!\
      Object valObj;
      if (keyObj instanceof String && Redaction.isRedactedKeyword((String) keyObj)) {
        valObj = REDACTED_VALUE;
      } else {
        valObj = entry.getValue(); // /!\ alien call /!\
      }
      serialize(
          keyObj,
          keyObj != null ? keyObj.getClass().getTypeName() : Object.class.getTypeName(),
          newLimits);
      serialize(
          valObj,
          valObj != null ? valObj.getClass().getTypeName() : Object.class.getTypeName(),
          newLimits);
      tokenWriter.mapEntryEpilogue(entry);
      i++;
    }
    return maxSize == mapSize;
  }
}
