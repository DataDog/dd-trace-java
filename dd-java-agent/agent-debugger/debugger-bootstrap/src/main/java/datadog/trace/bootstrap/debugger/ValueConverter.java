package datadog.trace.bootstrap.debugger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts an object instance to a string representation that will be stored into a Snapshot's
 * CapturedValue
 */
public class ValueConverter {
  private static final Logger log = LoggerFactory.getLogger(ValueConverter.class);

  public static final int DEFAULT_REFERENCE_DEPTH = 1;
  public static final int DEFAULT_COLLECTION_SIZE = 100;
  public static final int DEFAULT_LENGTH = 255;
  public static final int DEFAULT_FIELD_COUNT = 20;

  public static class Limits {
    public final int maxReferenceDepth;
    public final int maxCollectionSize;
    public final int maxLength;
    public final int maxFieldCount;

    public static final Limits DEFAULT =
        new Limits(
            DEFAULT_REFERENCE_DEPTH, DEFAULT_COLLECTION_SIZE, DEFAULT_LENGTH, DEFAULT_FIELD_COUNT);

    public Limits(int maxReferenceDepth, int maxCollectionSize, int maxLength, int maxFieldCount) {
      this.maxReferenceDepth = maxReferenceDepth;
      this.maxCollectionSize = maxCollectionSize;
      this.maxLength = maxLength;
      this.maxFieldCount = maxFieldCount;
    }
  }

  private static final ClassValue<Class<?>> toStringDeclaringClass =
      new ClassValue<Class<?>>() {
        @Override
        protected Class<?> computeValue(Class<?> type) {
          Method[] methods = type.getMethods();
          for (Method m : methods) {
            if ("toString".equals(m.getName()) && m.getParameterCount() == 0) {
              return m.getDeclaringClass();
            }
          }
          return Object.class;
        }
      };

  private static final Set<String> SAFE_TOSTRING_CLASS_NAMES =
      new HashSet<>(Arrays.asList("java.util.Date"));

  private static final ClassValue<Boolean> SAFE_TOSTRING_CLASSES =
      new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
          Package pack = type.getPackage();
          if (pack != null) {
            String packageName = pack.getName();
            if (packageName.equals("java.lang")) {
              // all classes from java.lang.* are considered safe
              return Boolean.TRUE;
            }
          }
          return SAFE_TOSTRING_CLASS_NAMES.contains(type.getName());
        }
      };

  private final Limits limits;

  public ValueConverter() {
    this(Limits.DEFAULT);
  }

  public ValueConverter(Limits limits) {
    this.limits = limits;
  }

  public String convert(Object value) {
    StringBuilder sb = new StringBuilder();
    doConvert(value, sb, limits, 0);
    return sb.toString();
  }

  public void convert(Object value, StringBuilder sb) {
    doConvert(value, sb, limits, 0);
  }

  private static void doConvert(
      Object value, StringBuilder sb, Limits limits, int currentReferenceDepth) {
    if (value == null) {
      toString(value, sb, limits, currentReferenceDepth);
      return;
    }
    // do not convert denied classes except String
    if (!(value instanceof String)
        && DebuggerContext.isDenied(value.getClass().getName())
        && !hasSafeToString(value)) {
      sb.append(value.getClass().getSimpleName()).append("(<DENIED>)");
      return;
    }
    if (value.getClass().isArray()) {
      if (value.getClass().getComponentType().isPrimitive()) {
        toStringArrayOfPrimitives(sb, value, limits);
        return;
      }
      handleArrays((Object[]) value, sb, limits, currentReferenceDepth);
      return;
    }
    if (value instanceof AbstractCollection) {
      handleCollections((AbstractCollection<?>) value, sb, limits, currentReferenceDepth);
      return;
    }
    if (value instanceof AbstractMap) {
      handleMap((AbstractMap<?, ?>) value, sb, limits, currentReferenceDepth);
      return;
    }
    if (hasSafeToString(value)) {
      String str = null;
      try {
        str = String.valueOf(value);
        str = truncate(str, limits.maxLength);
      } catch (Exception e) {
        if (log.isDebugEnabled()) {
          log.debug("Cannot call toString on class[{}]", value.getClass().getName(), e);
        } else {
          log.info(
              "Cannot call to String on class[{}] because: {}",
              value.getClass().getName(),
              e.toString());
        }
      }
      sb.append(str);
    } else {
      toString(value, sb, limits, currentReferenceDepth);
    }
  }

  private static void handleArrays(
      Object[] array, StringBuilder sb, Limits limits, int currentReferenceDepth) {
    if (array.length == 0) {
      sb.append("[]");
      return;
    }
    boolean truncated = false;
    int arrayLength = array.length;
    if (arrayLength > limits.maxCollectionSize) {
      arrayLength = limits.maxCollectionSize;
      truncated = true;
    }
    sb.append("[");
    int initialSize = sb.length();
    for (int i = 0; i < arrayLength; i++) {
      if (sb.length() > initialSize) {
        sb.append(", ");
      }
      Object o = array[i];
      if (o == array) {
        sb.append("[...]");
      } else if (currentReferenceDepth < limits.maxReferenceDepth) {
        doConvert(o, sb, limits, currentReferenceDepth + 1);
      } else {
        sb.append(o);
      }
    }
    if (truncated) {
      sb.append(", ...");
    }
    sb.append(']');
  }

  private static boolean hasSafeToString(Object o) {
    if (o == null) {
      return false;
    }
    return SAFE_TOSTRING_CLASSES.get(o.getClass());
  }

  private static boolean filterIn(Field f) {
    // Jacoco insert a transient field
    if ("$jacocoData".equals(f.getName()) && Modifier.isTransient(f.getModifiers())) {
      return false;
    }
    return true;
  }

  private static void convertField(
      StringBuilder sb,
      int initialSize,
      int currentReferenceDepth,
      Limits limits,
      Field f,
      Object value) {
    if (sb.length() > initialSize) {
      sb.append(", ");
    }
    sb.append(f.getName()).append("=");
    if (currentReferenceDepth < limits.maxReferenceDepth) {
      doConvert(value, sb, limits, currentReferenceDepth + 1);
    } else {
      if (value != null) {
        String str = hasSafeToString(value) ? String.valueOf(value) : value.getClass().getName();
        str = truncate(str, limits.maxLength);
        sb.append(str);
      } else {
        sb.append("null");
      }
    }
  }

  private static void handleConvertException(
      Exception ex, Field f, StringBuilder sb, int initialSize, String className) {
    String fieldName = f.getName();
    if (log.isDebugEnabled()) {
      log.debug("Cannot convert field[{}] from class[{}]", fieldName, className, ex);
    }
    // indicates in any cases that we had an error trying to convert the field
    if (sb.length() > initialSize) {
      sb.append(", ");
    }
    sb.append(fieldName).append("=<NOT_CAPTURED>");
  }

  private static void toString(
      Object o, StringBuilder sb, Limits limits, int currentReferenceDepth) {
    if (o == null) {
      sb.append("null");
      return;
    }
    int startIdx = sb.length();
    String className = o.getClass().getSimpleName();
    sb.append(className).append('(');
    int initialSize = sb.length();
    Fields.processFields(
        o,
        ValueConverter::filterIn,
        (f, value) -> convertField(sb, initialSize, currentReferenceDepth, limits, f, value),
        (ex, f) -> handleConvertException(ex, f, sb, initialSize, className),
        (f, total) -> {},
        limits.maxFieldCount);
    if (sb.length() - startIdx > limits.maxLength) {
      sb.delete(startIdx + limits.maxLength, sb.length());
      sb.append("...");
    }
    sb.append(')');
  }

  private static void toStringArrayOfPrimitives(StringBuilder sb, Object value, Limits limits) {
    Class<?> componentType = value.getClass().getComponentType();
    if (componentType == long.class) {
      appendLongArray(sb, (long[]) value, limits.maxCollectionSize);
    } else if (componentType == int.class) {
      appendIntArray(sb, (int[]) value, limits.maxCollectionSize);
    } else if (componentType == short.class) {
      appendShortArray(sb, (short[]) value, limits.maxCollectionSize);
    } else if (componentType == char.class) {
      appendCharArray(sb, (char[]) value, limits.maxCollectionSize);
    } else if (componentType == byte.class) {
      appendByteArray(sb, (byte[]) value, limits.maxCollectionSize);
    } else if (componentType == boolean.class) {
      appendBooleanArray(sb, (boolean[]) value, limits.maxCollectionSize);
    } else if (componentType == float.class) {
      appendFloatArray(sb, (float[]) value, limits.maxCollectionSize);
    } else if (componentType == double.class) {
      appendDoubleArray(sb, (double[]) value, limits.maxCollectionSize);
    } else {
      throw new IllegalArgumentException("Unsupported primitive array: " + value.getClass());
    }
  }

  private static void appendLongArray(StringBuilder sb, long[] longArray, int maxSize) {
    int idxMax = longArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(longArray[i]);
      if (i == idxMax) {
        if (longArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendIntArray(StringBuilder sb, int[] intArray, int maxSize) {
    int idxMax = intArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(intArray[i]);
      if (i == idxMax) {
        if (intArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendShortArray(StringBuilder sb, short[] shortArray, int maxSize) {
    int idxMax = shortArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(shortArray[i]);
      if (i == idxMax) {
        if (shortArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendCharArray(StringBuilder sb, char[] charArray, int maxSize) {
    int idxMax = charArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(charArray[i]);
      if (i == idxMax) {
        if (charArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendByteArray(StringBuilder sb, byte[] byteArray, int maxSize) {
    int idxMax = byteArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(byteArray[i]);
      if (i == idxMax) {
        if (byteArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendBooleanArray(StringBuilder sb, boolean[] booleanArray, int maxSize) {
    int idxMax = booleanArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(booleanArray[i]);
      if (i == idxMax) {
        if (booleanArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendFloatArray(StringBuilder sb, float[] floatArray, int maxSize) {
    int idxMax = floatArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(floatArray[i]);
      if (i == idxMax) {
        if (floatArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void appendDoubleArray(StringBuilder sb, double[] doubleArray, int maxSize) {
    int idxMax = doubleArray.length - 1;
    if (idxMax == -1) {
      sb.append("[]");
      return;
    }
    idxMax = Math.min(idxMax, maxSize - 1);
    sb.append('[');
    int i = 0;
    while (i <= idxMax) {
      sb.append(doubleArray[i]);
      if (i == idxMax) {
        if (doubleArray.length > maxSize) {
          sb.append(", ...");
        }
        sb.append(']');
        return;
      }
      sb.append(", ");
      i++;
    }
  }

  private static void handleCollections(
      AbstractCollection<?> collection,
      StringBuilder sb,
      Limits limits,
      int currentReferenceDepth) {
    if (collection.isEmpty()) {
      sb.append("[]");
      return;
    }
    sb.append("[");
    int initialSize = sb.length();
    int count = 0;
    for (Object o : collection) {
      if (sb.length() > initialSize) {
        sb.append(", ");
      }
      if (o == collection) {
        sb.append("(this Collection)");
      } else {
        doConvert(o, sb, limits, currentReferenceDepth + 1);
      }
      count++;
      if (count >= limits.maxCollectionSize) {
        sb.append(", ...");
        break;
      }
    }
    sb.append(']');
  }

  private static void handleMap(
      AbstractMap<?, ?> map, StringBuilder sb, Limits limits, int currentReferenceDepth) {
    if (map.isEmpty()) {
      sb.append("{}");
      return;
    }
    sb.append("{");
    int initialSize = sb.length();
    int count = 0;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (sb.length() > initialSize) {
        sb.append(", ");
      }
      Object key = entry.getKey();
      Object value = entry.getValue();
      if (key == map) {
        sb.append("(this Map)");
      } else if (currentReferenceDepth < limits.maxReferenceDepth) {
        doConvert(key, sb, limits, currentReferenceDepth + 1);
      } else {
        sb.append(key);
      }
      sb.append("=");
      if (value == map) {
        sb.append("(this Map)");
      } else if (currentReferenceDepth < limits.maxReferenceDepth) {
        doConvert(value, sb, limits, currentReferenceDepth + 1);
      } else {
        sb.append(value);
      }
      count++;
      if (count >= limits.maxCollectionSize) {
        sb.append(", ...");
        break;
      }
    }
    sb.append('}');
  }

  private static String truncate(String s, int maxLength) {
    if (s != null && s.length() > maxLength) {
      return s.substring(0, maxLength) + "...";
    }
    return s;
  }
}
